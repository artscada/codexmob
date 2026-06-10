param(
    [Parameter(Mandatory = $true)]
    [string]$BaseUrl,

    [Parameter(Mandatory = $true)]
    [string]$Token,

    [string]$CallbackHost,
    [int]$CallbackPort = 18082,
    [switch]$SkipCallback,
    [switch]$SkipCancel
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)

$utf8 = [System.Text.UTF8Encoding]::new($false)
$script:Summary = New-Object System.Collections.Generic.List[object]
$script:WorkingRoot = Join-Path $env:TEMP ('codex_http_test_' + [DateTime]::Now.ToString('yyyyMMdd_HHmmss'))
New-Item -ItemType Directory -Path $script:WorkingRoot -Force | Out-Null

if (-not $SkipCallback -and [string]::IsNullOrWhiteSpace($CallbackHost)) {
    throw 'Для async callback укажи -CallbackHost с LAN IP этого ПК, либо добавь -SkipCallback.'
}

function Write-Section {
    param([string]$Title)
    Write-Host ""
    Write-Host ("=== " + $Title + " ===")
}

function Add-Summary {
    param([string]$Name, [bool]$Passed, [string]$Details)
    $script:Summary.Add([PSCustomObject]@{ Name = $Name; Passed = $Passed; Details = $Details }) | Out-Null
    $prefix = if ($Passed) { 'OK' } else { 'FAIL' }
    Write-Host ("[" + $prefix + "] " + $Name + " - " + $Details)
}

function Convert-FromJsonSafe {
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) { return $null }
    try { return $Text | ConvertFrom-Json } catch { return $null }
}

function New-HttpClient {
    param([int]$TimeoutSec = 180)
    $client = [System.Net.Http.HttpClient]::new()
    $client.Timeout = [TimeSpan]::FromSeconds($TimeoutSec)
    $client.DefaultRequestHeaders.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $Token)
    return $client
}

function New-JsonContent {
    param([hashtable]$Body)
    $json = $Body | ConvertTo-Json -Depth 20 -Compress
    $bytes = $utf8.GetBytes($json)
    $content = [System.Net.Http.ByteArrayContent]::new($bytes)
    $content.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse('application/json; charset=utf-8')
    return [PSCustomObject]@{ Json = $json; Content = $content }
}

function Invoke-Api {
    param(
        [ValidateSet('GET', 'POST')]
        [string]$Method,
        [string]$Url,
        [hashtable]$Body,
        [int]$TimeoutSec = 180
    )

    $client = New-HttpClient -TimeoutSec $TimeoutSec
    try {
        $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::$Method, $Url)
        if ($Method -eq 'POST' -and $null -ne $Body) {
            $payload = New-JsonContent -Body $Body
            $request.Content = $payload.Content
        }
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $raw = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        return [PSCustomObject]@{ StatusCode = [int]$response.StatusCode; Raw = $raw; Json = Convert-FromJsonSafe $raw }
    }
    finally {
        $client.Dispose()
    }
}

function New-Utf8NoBomFile {
    param([string]$Path, [string]$Text)
    [System.IO.File]::WriteAllText($Path, $Text, $utf8)
}

function Get-PythonCommand {
    $py = Get-Command py -ErrorAction SilentlyContinue
    if ($py) { return @($py.Source, '-3') }
    $python = Get-Command python -ErrorAction SilentlyContinue
    if ($python) { return @($python.Source) }
    throw 'Python не найден. Для callback listener нужен py или python в PATH.'
}

function Start-CallbackListener {
    param([string]$BindAddress, [int]$Port)

    $listenerDir = Join-Path $script:WorkingRoot 'callback'
    New-Item -ItemType Directory -Path $listenerDir -Force | Out-Null

    $scriptPath = Join-Path $listenerDir 'listener.py'
    $payloadPath = Join-Path $listenerDir 'payload.json'
    $readyPath = Join-Path $listenerDir 'ready.txt'
    $stdoutPath = Join-Path $listenerDir 'stdout.log'
    $stderrPath = Join-Path $listenerDir 'stderr.log'

    $pythonCode = @"
import http.server
import sys

payload_path = sys.argv[1]
ready_path = sys.argv[2]
host = sys.argv[3]
port = int(sys.argv[4])

class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get('Content-Length', '0'))
        body = self.rfile.read(length)
        with open(payload_path, 'wb') as f:
            f.write(body)
        self.send_response(200)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.end_headers()
        self.wfile.write(b'{"ok":true}')

    def do_GET(self):
        self.send_response(200)
        self.send_header('Content-Type', 'text/plain; charset=utf-8')
        self.end_headers()
        self.wfile.write(b'ready')

    def log_message(self, fmt, *args):
        pass

class ReusableServer(http.server.ThreadingHTTPServer):
    allow_reuse_address = True

server = ReusableServer((host, port), Handler)
with open(ready_path, 'w', encoding='utf-8') as f:
    f.write('ready')
server.serve_forever()
"@

    New-Utf8NoBomFile -Path $scriptPath -Text $pythonCode

    $pythonCmd = Get-PythonCommand
    $argumentList = if ($pythonCmd.Count -eq 1) {
        @($scriptPath, $payloadPath, $readyPath, $BindAddress, [string]$Port)
    } else {
        @($pythonCmd[1..($pythonCmd.Count - 1)] + @($scriptPath, $payloadPath, $readyPath, $BindAddress, [string]$Port))
    }

    $process = Start-Process -FilePath $pythonCmd[0] -ArgumentList $argumentList -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath -PassThru -WindowStyle Hidden
    $deadline = (Get-Date).AddSeconds(10)
    while ((Get-Date) -lt $deadline) {
        if (Test-Path $readyPath) {
            try {
                $probe = Invoke-WebRequest -Uri ('http://127.0.0.1:' + $Port + '/') -UseBasicParsing -TimeoutSec 2
                if ($probe.StatusCode -eq 200) {
                    return [PSCustomObject]@{ Process = $process; PayloadPath = $payloadPath }
                }
            } catch {
            }
        }
        Start-Sleep -Milliseconds 200
    }

    throw 'Не удалось поднять callback listener.'
}

function Stop-Listener {
    param([object]$Listener)
    if ($null -eq $Listener) { return }
    try {
        if (-not $Listener.Process.HasExited) {
            Stop-Process -Id $Listener.Process.Id -Force
        }
    } catch {
    }
}

function Wait-ForCallbackFile {
    param([string]$Path, [int]$TimeoutSec = 90)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (Test-Path $Path) {
            Start-Sleep -Milliseconds 300
            return Get-Content -Path $Path -Raw -Encoding UTF8
        }
        Start-Sleep -Milliseconds 500
    }
    return $null
}

function Parse-SseText {
    param([string]$Text)
    $events = New-Object System.Collections.Generic.List[object]
    $currentEvent = ''
    $dataLines = New-Object System.Collections.Generic.List[string]
    foreach ($line in ($Text -replace "`r", '' -split "`n")) {
        if ($line -eq '') {
            if ($currentEvent -or $dataLines.Count -gt 0) {
                $dataText = [string]::Join("`n", $dataLines)
                $events.Add([PSCustomObject]@{ Event = $currentEvent; Data = $dataText; Json = Convert-FromJsonSafe $dataText }) | Out-Null
            }
            $currentEvent = ''
            $dataLines.Clear()
            continue
        }
        if ($line.StartsWith('event:')) { $currentEvent = $line.Substring(6).Trim(); continue }
        if ($line.StartsWith('data:')) { $dataLines.Add($line.Substring(5).TrimStart()) }
    }
    if ($currentEvent -or $dataLines.Count -gt 0) {
        $dataText = [string]::Join("`n", $dataLines)
        $events.Add([PSCustomObject]@{ Event = $currentEvent; Data = $dataText; Json = Convert-FromJsonSafe $dataText }) | Out-Null
    }
    return $events
}

function Start-SseCurl {
    param([hashtable]$Body, [string]$OutputPath, [string]$ErrorPath)
    $payloadPath = Join-Path $script:WorkingRoot ([System.Guid]::NewGuid().ToString() + '.json')
    New-Utf8NoBomFile -Path $payloadPath -Text (($Body | ConvertTo-Json -Depth 20 -Compress))
    $args = @(
        '-sS','-N','-X','POST',
        '-H', ('"Authorization: Bearer ' + $Token + '"'),
        '-H', '"Content-Type: application/json; charset=utf-8"',
        '-H', '"Accept: text/event-stream"',
        '--data-binary', ('"@' + $payloadPath.Replace('"', '""') + '"'),
        ('"' + ($BaseUrl.TrimEnd('/') + '/api/run-task') + '"')
    )
    $process = Start-Process -FilePath 'curl.exe' -ArgumentList ([string]::Join(' ', $args)) -RedirectStandardOutput $OutputPath -RedirectStandardError $ErrorPath -PassThru -WindowStyle Hidden
    return [PSCustomObject]@{ Process = $process; OutputPath = $OutputPath; ErrorPath = $ErrorPath }
}

function Read-TextIfExists {
    param([string]$Path)
    if (Test-Path $Path) { return Get-Content -Path $Path -Raw -Encoding UTF8 }
    return ''
}

function Wait-ForRunningTask {
    param([string]$TaskId, [int]$TimeoutSec = 30)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        $response = Invoke-Api -Method GET -Url ($BaseUrl.TrimEnd('/') + '/api/runs?status=running&limit=20')
        if ($null -ne $response.Json -and $null -ne $response.Json.runs) {
            if (@($response.Json.runs | Where-Object { $_.taskId -eq $TaskId }).Count -gt 0) {
                return $true
            }
        }
        Start-Sleep -Milliseconds 700
    }
    return $false
}

function Get-RunRecord {
    param([string]$TaskId)
    Invoke-Api -Method GET -Url ($BaseUrl.TrimEnd('/') + '/api/runs/' + $TaskId)
}

function Invoke-SyncTask {
    param([string]$TaskId, [string]$Prompt)
    Invoke-Api -Method POST -Url ($BaseUrl.TrimEnd('/') + '/api/run-task') -Body @{
        task_id = $TaskId
        prompt = $Prompt
        create_new_chat = $true
        create_if_none = $true
        stream = $false
        response_mode = 'sync'
    } -TimeoutSec 240
}

$listener = $null
try {
    Write-Section '1. Health'
    $health = Invoke-Api -Method GET -Url ($BaseUrl.TrimEnd('/') + '/api/health')
    Add-Summary -Name 'Health' -Passed ($health.StatusCode -eq 200) -Details ($health.Raw.Trim())

    Write-Section '2. Русский sync'
    $syncTaskId = 'task-sync-' + [DateTime]::Now.ToString('yyyyMMddHHmmss')
    $syncResult = Invoke-SyncTask -TaskId $syncTaskId -Prompt 'Ответь по-русски. В первой строке напиши: HTTP API работает. Во второй строке: Связь с телефоном установлена.'
    $syncText = if ($null -ne $syncResult.Json) { [string]$syncResult.Json.output } else { '' }
    Add-Summary -Name 'Русский sync' -Passed ($syncResult.StatusCode -eq 200 -and $syncText.Contains('HTTP API работает')) -Details $syncText

    Write-Section '3. Root-диагностика'
    $rootTaskId = 'task-root-' + [DateTime]::Now.ToString('yyyyMMddHHmmss')
    $rootResult = Invoke-SyncTask -TaskId $rootTaskId -Prompt 'Проверь наличие root. Если root есть, выполни одну безопасную диагностическую команду и кратко ответь по-русски.'
    $rootText = if ($null -ne $rootResult.Json) { [string]$rootResult.Json.output } else { '' }
    Add-Summary -Name 'Root-диагностика' -Passed ($rootResult.StatusCode -eq 200 -and -not [string]::IsNullOrWhiteSpace($rootText)) -Details $rootText

    Write-Section '4. SSE stream'
    $sseTaskId = 'task-sse-' + [DateTime]::Now.ToString('yyyyMMddHHmmss')
    $sseOut = Join-Path $script:WorkingRoot 'sse_output.txt'
    $sseErr = Join-Path $script:WorkingRoot 'sse_error.txt'
    $sseProc = Start-SseCurl -Body @{
        task_id = $sseTaskId
        prompt = 'Ответь только по-русски. Дай 5 коротких строк и закончи словом Готово.'
        create_new_chat = $true
        create_if_none = $true
        stream = $true
        response_mode = 'sync'
    } -OutputPath $sseOut -ErrorPath $sseErr
    $sseProc.Process.WaitForExit()
    $sseEvents = Parse-SseText -Text (Read-TextIfExists -Path $sseOut)
    $sseDone = $sseEvents | Where-Object { $_.Event -eq 'done' } | Select-Object -Last 1
    $sseText = if ($null -ne $sseDone -and $null -ne $sseDone.Json) { [string]$sseDone.Json.ai_response } else { '' }
    $sseSuccess = ($null -ne $sseDone -and $null -ne $sseDone.Json -and $sseDone.Json.success -eq $true -and $sseText.Contains('Готово'))
    Add-Summary -Name 'SSE stream' -Passed $sseSuccess -Details $sseText

    if (-not $SkipCallback) {
        Write-Section '5. Async callback'
        $listener = Start-CallbackListener -BindAddress '0.0.0.0' -Port $CallbackPort
        $callbackTaskId = 'task-callback-' + [DateTime]::Now.ToString('yyyyMMddHHmmss')
        $callbackResult = Invoke-Api -Method POST -Url ($BaseUrl.TrimEnd('/') + '/api/run-task') -Body @{
            task_id = $callbackTaskId
            prompt = 'Ответь по-русски в 2-3 строках и упомяни, что это callback-ответ устройства.'
            create_new_chat = $true
            create_if_none = $true
            stream = $false
            response_mode = 'async_callback'
            callback_url = ('http://' + $CallbackHost + ':' + $CallbackPort + '/callback/')
        } -TimeoutSec 60
        $callbackPayloadText = Wait-ForCallbackFile -Path $listener.PayloadPath -TimeoutSec 120
        $callbackPayload = Convert-FromJsonSafe $callbackPayloadText
        $callbackRun = Get-RunRecord -TaskId $callbackTaskId
        $ok = ($callbackResult.StatusCode -eq 202 -and $null -ne $callbackPayload -and $callbackPayload.success -eq $true -and $callbackRun.Json.status -eq 'callback_sent')
        $details = if ($null -ne $callbackPayload) { [string]$callbackPayload.output } else { 'callback не пришёл' }
        Add-Summary -Name 'Async callback' -Passed $ok -Details $details
        Stop-Listener -Listener $listener
        $listener = $null
    } else {
        Add-Summary -Name 'Async callback' -Passed $true -Details 'Пропущено по флагу -SkipCallback'
    }

    if (-not $SkipCancel) {
        Write-Section '6. Running + cancel'
        $cancelTaskId = 'task-cancel-' + [DateTime]::Now.ToString('yyyyMMddHHmmss')
        $cancelOut = Join-Path $script:WorkingRoot 'cancel_output.txt'
        $cancelErr = Join-Path $script:WorkingRoot 'cancel_error.txt'
        $cancelProc = Start-SseCurl -Body @{
            task_id = $cancelTaskId
            prompt = 'Ответь только по-русски. Сформируй очень длинный ответ из 180 строк и не сокращай вывод.'
            create_new_chat = $true
            create_if_none = $true
            stream = $true
            response_mode = 'sync'
        } -OutputPath $cancelOut -ErrorPath $cancelErr
        $runningSeen = Wait-ForRunningTask -TaskId $cancelTaskId -TimeoutSec 30
        $cancelResponse = if ($runningSeen) { Invoke-Api -Method POST -Url ($BaseUrl.TrimEnd('/') + '/api/runs/' + $cancelTaskId + '/cancel') -Body @{} -TimeoutSec 30 } else { $null }
        $cancelProc.Process.WaitForExit()
        $cancelEvents = Parse-SseText -Text (Read-TextIfExists -Path $cancelOut)
        $cancelError = $cancelEvents | Where-Object { $_.Event -eq 'error' } | Select-Object -Last 1
        $cancelRun = Get-RunRecord -TaskId $cancelTaskId
        $ok = ($runningSeen -and $null -ne $cancelResponse -and $cancelResponse.Json.cancelled -eq $true -and $cancelRun.Json.status -eq 'cancelled' -and $null -ne $cancelError -and $cancelError.Data.Contains('Request cancelled'))
        Add-Summary -Name 'Running + cancel' -Passed $ok -Details (if ($ok) { 'Отмена сработала корректно.' } else { 'Отмена не подтвердилась.' })
    } else {
        Add-Summary -Name 'Running + cancel' -Passed $true -Details 'Пропущено по флагу -SkipCancel'
    }
}
finally {
    Stop-Listener -Listener $listener
}

Write-Section 'Сводка'
$failed = @($script:Summary | Where-Object { -not $_.Passed })
$script:Summary | ForEach-Object {
    $prefix = if ($_.Passed) { 'OK' } else { 'FAIL' }
    Write-Host ("- [" + $prefix + "] " + $_.Name)
}

Write-Host ''
Write-Host ('Рабочая папка теста: ' + $script:WorkingRoot)

if ($failed.Count -eq 0) {
    Write-Host 'Итог: все основные режимы HTTP API прошли успешно.'
    exit 0
}

Write-Host ('Итог: есть непройденные проверки: ' + (($failed | ForEach-Object { $_.Name }) -join ', '))
exit 1
