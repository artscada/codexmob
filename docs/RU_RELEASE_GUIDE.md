# codex-android-RU: инструкция по самостоятельной установке и проверке

## 1. Что лежит в релизе

В опубликованном GitHub Release должны быть минимум два файла:

- `codex-android-ru-207-v1.11.0_7.apk`
- `SHA256SUMS.txt`

Этот релизный APK сейчас оформляется как `latest known good` со снятой рабочей сборки устройства `207`.

Этого достаточно, чтобы:

- скачать APK;
- установить на телефон;
- сверить контрольную сумму;
- включить HTTP API;
- прогнать тесты со своего ПК.

## 2. Что доработано в приложении

### 2.1. История и несколько чатов

- История чатов сохраняется локально.
- Можно создавать новые чаты и возвращаться к старым.
- Текущее выбранное окно диалога восстанавливается после перезапуска.

### 2.2. Локальный Codex

- В настройках можно задать адрес API, модель и ключ для локального Codex CLI.
- Конфигурация сохраняется и синхронизируется в локальную Linux-среду приложения.
- После настройки не требуется вручную переписывать конфиг в файловой системе телефона.

### 2.3. Внешний HTTP API

Поддерживаются:

- `GET /api/health`
- `POST /api/run-task`
- `GET /api/runs`
- `GET /api/runs/{task_id}`
- `POST /api/runs/{task_id}/cancel`

Режимы ответа:

- `response_mode = sync`
- `stream = true` через SSE
- `response_mode = async_callback`

### 2.4. Журнал задач

Каждый внешний запуск теперь можно отследить по `task_id`.

Статусы, которые уже реализованы:

- `accepted`
- `running`
- `succeeded`
- `failed`
- `callback_pending`
- `callback_sent`
- `callback_failed`
- `cancelled`

### 2.5. Root и shell

- Приложение умеет выполнять root/shell сценарии.
- Для опасных команд действуют ограничения безопасности.
- Для некоторых явных запросов используется прямой shell-маршрут вместо свободного ответа модели.

## 3. Установка APK

### Вариант 1. Через файловый менеджер телефона

1. Скачай APK из релиза.
2. Открой файл на телефоне.
3. Разреши установку из неизвестного источника, если Android попросит.
4. Установи приложение.

### Вариант 2. Через ADB с ПК

```powershell
adb install -r .\artifacts\release\codex-android-ru-207-v1.11.0_7.apk
```

Если устройство подключено по TCP/IP:

```powershell
adb -s 192.168.28.217:5555 install -r .\artifacts\release\codex-android-ru-207-v1.11.0_7.apk
```

Проверка SHA256 на Windows:

```powershell
Get-FileHash .\artifacts\release\codex-android-ru-207-v1.11.0_7.apk -Algorithm SHA256
Get-Content .\artifacts\release\SHA256SUMS.txt
```

## 4. Первичная настройка

### 4.1. Провайдер модели

Открой в приложении:

- `Настройки`
- `Способ подключения`

Дальше выбери один из вариантов:

- `Локальный Codex CLI`
- `OpenAI-совместимый API`

Заполни:

- адрес API;
- модель;
- API-ключ.

Затем нажми:

- `Проверить`
- `Применить` для локального режима.

### 4.2. Безопасность

Режимы безопасности:

- `safe` — shell запрещён, доступ к файлам только в песочнице;
- `standard` — shell запрещён, но доступ к файлам шире;
- `full` — полный доступ, включая shell/root.

Для инженерных и root-сценариев нужен именно `full`.

## 5. Включение HTTP API

Открой в приложении экран настроек HTTP API и:

1. включи сервис;
2. проверь порт;
3. сохрани Bearer token;
4. узнай IP телефона в локальной сети.

Проверка доступности:

```powershell
curl -H "Authorization: Bearer YOUR_TOKEN" "http://DEVICE_IP:8094/api/health"
```

Ожидаемый ответ:

```json
{
  "status": "ok",
  "enabled": true,
  "service_running": true,
  "port": 8094,
  "version_name": "..."
}
```

## 6. Самостоятельный тест с ПК

Для этого в репозитории лежит готовый скрипт:

- `tools/test_http_api_device.ps1`

Он проверяет:

- health;
- русский sync-ответ;
- root-диагностику;
- SSE;
- async callback;
- running + cancel.

Пример:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\test_http_api_device.ps1 `
  -BaseUrl "http://192.168.28.207:8094" `
  -Token "YOUR_BEARER_TOKEN" `
  -CallbackHost "192.168.28.230"
```

Если callback пока не нужен:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\test_http_api_device.ps1 `
  -BaseUrl "http://192.168.28.207:8094" `
  -Token "YOUR_BEARER_TOKEN" `
  -SkipCallback
```

## 7. Что уже считается подтверждённым

На реальном устройстве были проверены:

- `sync`
- `SSE`
- `async_callback`
- `GET /api/runs`
- `GET /api/runs/{task_id}`
- `cancel`
- root-доступ

## 8. Как тестировать вручную без скрипта

### 8.1. Sync

```powershell
$h = @{ Authorization = 'Bearer YOUR_TOKEN' }
$payload = @{
  task_id = 'task-sync-001'
  prompt = 'Ответь по-русски: телефон готов к работе?'
  create_new_chat = $true
  response_mode = 'sync'
} | ConvertTo-Json -Compress

Invoke-RestMethod -Headers $h -Method POST -Uri 'http://DEVICE_IP:8094/api/run-task' -ContentType 'application/json; charset=utf-8' -Body $payload
```

### 8.2. SSE

```powershell
curl.exe -N -X POST \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json; charset=utf-8" \
  -H "Accept: text/event-stream" \
  --data-binary '{"task_id":"task-stream-001","prompt":"Ответь коротко по-русски","stream":true}' \
  "http://DEVICE_IP:8094/api/run-task"
```

### 8.3. Async callback

```powershell
$payload = @{
  task_id = 'task-callback-001'
  prompt = 'Ответь по-русски коротко: callback работает.'
  response_mode = 'async_callback'
  callback_url = 'http://YOUR_PC_IP:18082/callback/'
} | ConvertTo-Json -Compress
```

### 8.4. Просмотр журнала задач

```powershell
curl -H "Authorization: Bearer YOUR_TOKEN" "http://DEVICE_IP:8094/api/runs?limit=20"
```

### 8.5. Отмена задачи

```powershell
curl -X POST -H "Authorization: Bearer YOUR_TOKEN" "http://DEVICE_IP:8094/api/runs/task-cancel-001/cancel"
```

## 9. Известные ограничения

- Некоторые длинные локальные диагностические запросы могут висеть в `running` слишком долго.
- Для стабильной инженерной работы лучше использовать не свободный вопрос, а строгий структурированный prompt и отдельный `task_id`.
- Русификация интерфейса ещё не завершена полностью.

## 10. Что не включено в репозиторий намеренно

В репозиторий и релиз не должны попадать:

- реальные API-ключи;
- Bearer token устройств;
- приватные скриншоты пользователя;
- временные dump/xml/log файлы;
- локальные sqlite-кэши и одноразовые отладочные артефакты.

## 11. Повторная сборка релиза

```powershell
./gradlew.bat assembleDebug
Copy-Item .\app\build\outputs\apk\debug\codex-android-debug.apk .\artifacts\release\codex-android-ru-debug-20260602.apk -Force
Get-FileHash .\artifacts\release\codex-android-ru-debug-20260602.apk -Algorithm SHA256
```

Если prebuilt-файлы подтягиваются из репозитория впервые, убедись, что Git LFS установлен и активирован:

```powershell
git lfs install
git lfs pull
```
