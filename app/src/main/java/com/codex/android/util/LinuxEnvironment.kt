package com.codex.android.util

import android.content.Context
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/**
 * 自包含 Linux 环境管理器。
 *
 * 负责：
 * - 管理 APK 中打包的 proot 引擎（位于 native libs 目录）
 * - 下载并解压 Ubuntu rootfs
 * - 提供 proot 执行包装器
 * - 状态检测与报告
 *
 * Android 10+ W^X 限制：只能执行 native libs 目录中的二进制。
 * proot 二进制已打包为 libproot.so（随 APK 安装时自动提取到 native libs）。
 */
class LinuxEnvironment(private val context: Context) {

    companion object {
        private const val TAG = "LinuxEnvironment"

        private const val ROOTFS_DIR = "linux-rootfs"
        private const val ROOTFS_ARCHIVE = "ubuntu-base.tar.gz"
        private val BUNDLED_ROOTFS_ASSETS = listOf(
            "prebuilt/farm-base-rootfs.tar.gz",
            "prebuilt/farm-base-rootfs.tar"
        )

        private val ROOTFS_MIRRORS = listOf(
            "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://mirrors.ustc.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://mirrors.nju.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://mirrors.sjtug.sjtu.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://mirrors.huaweicloud.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://mirrors.tencent.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz"
        )

        private const val CONNECT_TIMEOUT_MS = 10_000
    }

    enum class EngineState {
        UNAVAILABLE,
        NOT_INSTALLED,
        INSTALLING,
        READY,
        ERROR
    }

    data class LinuxEnvInfo(
        val state: EngineState = EngineState.UNAVAILABLE,
        val prootPath: String = "",
        val prootLoaderPath: String = "",
        val prootLoader32Path: String = "",
        val rootfsPath: String = "",
        val tallocPath: String = "",
        val errorMessage: String = ""
    )

    fun getInfo(): LinuxEnvInfo {
        val ai = context.packageManager?.getApplicationInfo(context.packageName, 0)
        val nativeLibraryDir = ai?.nativeLibraryDir
        if (nativeLibraryDir == null) {
            return LinuxEnvInfo(EngineState.UNAVAILABLE, errorMessage = "Не удалось получить каталог native-библиотек")
        }
        val proot = File(nativeLibraryDir, "libproot.so")
        val loader = File(nativeLibraryDir, "libproot-loader.so")
        val loader32 = File(nativeLibraryDir, "libproot-loader32.so")
        val talloc = File(nativeLibraryDir, "libtalloc.so")
        val rootfs = getRootfsDir()

        return when {
            !proot.canExecute() ->
                LinuxEnvInfo(EngineState.UNAVAILABLE, errorMessage = "Движок proot не готов")
            !loader.canExecute() ->
                LinuxEnvInfo(EngineState.UNAVAILABLE, errorMessage = "Загрузчик proot не готов")
            !rootfs.isDirectory() || rootfs.listFiles()?.isEmpty() != false ->
                LinuxEnvInfo(EngineState.NOT_INSTALLED,
                    prootPath = proot.path, prootLoaderPath = loader.path,
                    prootLoader32Path = loader32.path, tallocPath = talloc.path)
            !isRootfsValid() ->
                LinuxEnvInfo(EngineState.ERROR,
                    prootPath = proot.path, prootLoaderPath = loader.path,
                    prootLoader32Path = loader32.path, tallocPath = talloc.path,
                    errorMessage = "rootfs поврежден или неполный")
            else ->
                LinuxEnvInfo(EngineState.READY,
                    prootPath = proot.path, prootLoaderPath = loader.path,
                    prootLoader32Path = loader32.path, tallocPath = talloc.path,
                    rootfsPath = rootfs.path)
        }
    }

    fun getRootfsDir(): File = File(context.filesDir, ROOTFS_DIR)

    fun isRootfsValid(): Boolean {
        val rootfs = getRootfsDir()
        if (!rootfs.isDirectory()) return false
        return File(rootfs, "bin/bash").canExecute() || File(rootfs, "bin/sh").canExecute()
    }

    fun isInstalled(): Boolean = getInfo().state == EngineState.READY

    fun getProotEnv(): Map<String, String> {
        val info = getInfo()
        val env = mutableMapOf<String, String>()
        if (info.prootLoaderPath.isNotEmpty()) env["PROOT_LOADER"] = info.prootLoaderPath
        if (info.prootLoader32Path.isNotEmpty()) env["PROOT_LOADER_32"] = info.prootLoader32Path

        val libLinkDir = File(context.cacheDir, "proot-libs")
        libLinkDir.mkdirs()
        val tallocCompat = File(libLinkDir, "libtalloc.so.2")
        if (info.tallocPath.isNotEmpty()) {
            ensureCompatLibrary(File(info.tallocPath), tallocCompat)
        }

        val ai2 = context.packageManager?.getApplicationInfo(context.packageName, 0)
        val nativeLibraryDir = ai2?.nativeLibraryDir ?: ""
        val prootTmpDir = File(context.cacheDir, "proot-tmp").also { it.mkdirs() }
        env["PROOT_TMP_DIR"] = prootTmpDir.path
        env["TMPDIR"] = prootTmpDir.path
        env["LD_LIBRARY_PATH"] = "$nativeLibraryDir:${libLinkDir.path}:/system/lib64:/system/lib"
        return env
    }

    private fun ensureCompatLibrary(source: File, target: File) {
        try {
            if (!source.exists()) return

            val targetPath = target.toPath()
            val needsRefresh = !target.exists() ||
                Files.isSymbolicLink(targetPath) ||
                target.length() != source.length()

            if (!needsRefresh) return

            if (target.exists() || Files.isSymbolicLink(targetPath)) {
                target.delete()
            }

            source.inputStream().use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            target.setReadable(true, false)
            Log.i(TAG, "Подготовлена совместимая библиотека: ${target.path}")
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось подготовить совместимую библиотеку ${target.name}", e)
        }
    }

    fun buildProotCommand(command: String): List<String> {
        val info = getInfo()
        val rootfs = getRootfsDir().path
        val packageDataDir = "/data/data/${context.packageName}"
        val guestPackageDataDir = "$rootfs/data/data/${context.packageName}"
        setupRootfs(File(rootfs))
        val guestScriptPath = prepareGuestCommandScript(command)
        return listOf(
            info.prootPath,
            "--rootfs=$rootfs",
            "--root-id",
            "--kill-on-exit",
            "-0",
            "-L",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "$packageDataDir:$guestPackageDataDir",
            "-b", "/storage",
            "-w", "/root",
            "/bin/bash", guestScriptPath
        )
    }

    fun createProotProcess(command: String, extraEnv: Map<String, String> = emptyMap()): Process {
        val prootEnv = getProotEnv() + extraEnv

        return if (AndroidShellExecutor.isRootAvailable()) {
            ProcessBuilder("su", "-c", buildProotShellCommand(command, prootEnv))
                .redirectErrorStream(false)
                .start()
        } else {
            val cmd = buildProotCommand(command)
            ProcessBuilder(cmd)
                .apply { environment().putAll(prootEnv) }
                .redirectErrorStream(false)
                .start()
        }
    }

    private fun buildProotShellCommand(command: String, env: Map<String, String>): String {
        val exports = env.entries.joinToString("; ") { (key, value) ->
            "export $key=${shellQuote(value)}"
        }
        val execArgs = buildProotCommand(command).joinToString(" ") { shellQuote(it) }
        return if (exports.isBlank()) {
            "exec $execArgs"
        } else {
            "$exports; exec $execArgs"
        }
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    private fun prepareGuestCommandScript(command: String): String {
        val rootfsDir = getRootfsDir()
        val guestRelativePath = "tmp/codex-command-${System.currentTimeMillis()}.sh"
        val hostScript = File(rootfsDir, guestRelativePath)
        hostScript.parentFile?.mkdirs()
        hostScript.writeText(
            """
            #!/bin/bash
            export HOME=/root
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            export TERM=xterm-256color
            export LANG=C.UTF-8
            export SHELL=/bin/bash
            export USER=root
            $command
            """.trimIndent() + "\n"
        )
        hostScript.setExecutable(true, false)
        return "/$guestRelativePath"
    }

    suspend fun runCommand(
        command: String,
        timeoutMs: Long = 120_000L
    ): AndroidShellExecutor.ShellResult = withContext(Dispatchers.IO) {
        val info = getInfo()
        if (info.state != EngineState.READY) {
            return@withContext AndroidShellExecutor.ShellResult(-1, "",
                "Встроенная Linux-среда не готова: ${info.errorMessage}")
        }

        try {
            val process = createProotProcess(command)
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)

            if (!finished) {
                process.destroyForcibly()
                AndroidShellExecutor.ShellResult(-1, stdout, stderr, isTimedOut = true)
            } else {
                AndroidShellExecutor.ShellResult(process.exitValue(), stdout, stderr)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось выполнить команду через proot", e)
            AndroidShellExecutor.ShellResult(-1, "", "Ошибка выполнения: ${e.message}")
        }
    }

    suspend fun installRootfs(
        onProgress: ((Long, Long) -> Unit)? = null,
        onStatus: ((String) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val rootfs = getRootfsDir()
            if (rootfs.isDirectory()) {
                onStatus?.invoke("Удаляю старый rootfs...")
                rootfs.deleteRecursively()
            }
            rootfs.mkdirs()

            val bundledAsset = findBundledRootfsAsset()
            if (bundledAsset != null) {
                onStatus?.invoke("Найден встроенный образ: $bundledAsset")
                onStatus?.invoke("Распаковываю встроенную Linux-среду farm-base...")
                val bundledSize = getBundledRootfsLength(bundledAsset)
                context.assets.open(bundledAsset).use { input ->
                    extractRootfs(input, rootfs, bundledSize, onProgress)
                }

                if (isRootfsValid()) {
                    onStatus?.invoke("Встроенная Linux-среда успешно установлена")
                    setupRootfs(rootfs)
                    return@withContext true
                }

                onStatus?.invoke("Проверка встроенной Linux-среды после распаковки не пройдена")
                return@withContext false
            }

            val archive = File(context.cacheDir, ROOTFS_ARCHIVE)

            onStatus?.invoke("Скачиваю Ubuntu rootfs (~37 МБ)...")
            var downloaded = false
            for (mirror in ROOTFS_MIRRORS) {
                onStatus?.invoke("Пробую зеркало: $mirror")
                try {
                    val conn = URL(mirror).openConnection() as HttpURLConnection
                    conn.connectTimeout = CONNECT_TIMEOUT_MS
                    conn.readTimeout = 180_000
                    conn.instanceFollowRedirects = true
                    conn.connect()

                    if (conn.responseCode != HttpURLConnection.HTTP_OK) continue

                    val total = conn.contentLengthLong
                    onStatus?.invoke("Начинаю загрузку (${total / 1024 / 1024} МБ)...")
                    val input = conn.inputStream
                    val output = FileOutputStream(archive)
                    val buffer = ByteArray(8192)
                    var read: Int
                    var totalRead = 0L
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        totalRead += read.toLong()
                        onProgress?.invoke(totalRead, total)
                    }
                    output.close()
                    input.close()
                    conn.disconnect()

                    if (archive.length() > 1_000_000) {
                        downloaded = true
                        onStatus?.invoke("Загрузка завершена (${archive.length() / 1024 / 1024} МБ)")
                        break
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Не удалось скачать с зеркала: $mirror", e)
                    onStatus?.invoke("Зеркало недоступно, пробую следующее...")
                }
            }

            if (!downloaded) {
                onStatus?.invoke("Все зеркала недоступны, загрузка не удалась")
                return@withContext false
            }

            onStatus?.invoke("Распаковываю rootfs...")
            extractRootfs(archive, rootfs, onProgress)

            archive.delete()

            if (isRootfsValid()) {
                onStatus?.invoke("Ubuntu rootfs успешно установлен")
                setupRootfs(rootfs)
                return@withContext true
            } else {
                onStatus?.invoke("Проверка rootfs после распаковки не пройдена")
                return@withContext false
            }
        } catch (e: Exception) {
            onStatus?.invoke("Ошибка установки: ${e.message}")
            Log.e(TAG, "Не удалось установить rootfs", e)
            false
        }
    }

    private fun extractRootfs(
        archive: File,
        dest: File,
        onProgress: ((Long, Long) -> Unit)? = null
    ) {
        archive.inputStream().use { input ->
            extractRootfs(input, dest, archive.length(), onProgress)
        }
    }

    private fun extractRootfs(
        archiveInput: InputStream,
        dest: File,
        totalBytes: Long?,
        onProgress: ((Long, Long) -> Unit)? = null
    ) {
        try {
            val progressTotal = totalBytes?.takeIf { it > 0L } ?: -1L
            val bufferedInput = BufferedInputStream(archiveInput)
            val countingInput = object : FilterInputStream(bufferedInput) {
                var bytesReadTotal = 0L

                override fun read(): Int {
                    val value = super.read()
                    if (value >= 0) bytesReadTotal++
                    return value
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    val read = super.read(buffer, offset, length)
                    if (read > 0) bytesReadTotal += read.toLong()
                    return read
                }
            }

            bufferedInput.mark(4)
            val magic = ByteArray(2)
            val readMagic = bufferedInput.read(magic)
            bufferedInput.reset()
            val isGzip = readMagic == 2 && magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte()

            val tarStream = if (isGzip) {
                TarArchiveInputStream(GZIPInputStream(countingInput))
            } else {
                TarArchiveInputStream(countingInput)
            }

            tarStream.use { tar ->
                    val destPath = dest.canonicalFile.toPath()
                    var archiveRootPrefix: String? = null
                    var entry = tar.nextTarEntry
                    while (entry != null) {
                        val rawName = entry.name.replace('\\', '/').trimStart('/')
                        if (archiveRootPrefix == null && rawName.isNotBlank()) {
                            val candidate = rawName.substringBefore('/').removeSuffix("/")
                            if (candidate.endsWith("-rootfs")) {
                                archiveRootPrefix = candidate
                                Log.i(TAG, "Обнаружен корневой префикс архива: $candidate")
                            }
                        }

                        val normalizedName = archiveRootPrefix?.let { prefix ->
                            when {
                                rawName == prefix || rawName == "$prefix/" -> ""
                                rawName.startsWith("$prefix/") -> rawName.removePrefix("$prefix/")
                                else -> rawName
                            }
                        } ?: rawName

                        if (normalizedName.isBlank()) {
                            val reported = if (progressTotal > 0) {
                                countingInput.bytesReadTotal.coerceAtMost(progressTotal)
                            } else {
                                countingInput.bytesReadTotal
                            }
                            onProgress?.invoke(reported, progressTotal)
                            entry = tar.nextTarEntry
                            continue
                        }

                        val target = File(dest, normalizedName)
                        val targetPath = target.canonicalFile.toPath()
                        if (!targetPath.startsWith(destPath)) {
                            throw IllegalStateException("Недопустимый путь в архиве: ${entry.name}")
                        }

                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else if (entry.isSymbolicLink) {
                            target.parentFile?.mkdirs()
                            try {
                                Os.symlink(entry.linkName ?: "", target.path)
                            } catch (_: Exception) {}
                        } else if (entry.isLink) {
                            // Жесткие ссылки в rootfs встречаются редко. Если система не дает их создать,
                            // пропускаем запись, чтобы установка не падала целиком.
                        } else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { out ->
                                val buffer = ByteArray(8192)
                                var remaining = entry.size.coerceAtLeast(0L)
                                while (remaining > 0) {
                                    val chunkSize = minOf(buffer.size.toLong(), remaining).toInt()
                                    val read = tar.read(buffer, 0, chunkSize)
                                    if (read <= 0) {
                                        throw IllegalStateException("Неожиданный конец архива при распаковке ${entry.name}")
                                    }
                                    out.write(buffer, 0, read)
                                    remaining -= read.toLong()
                                }
                            }
                            if (entry.mode and 64 != 0) {
                                target.setExecutable(true, false)
                            }
                        }
                        val reported = if (progressTotal > 0) {
                            countingInput.bytesReadTotal.coerceAtMost(progressTotal)
                        } else {
                            countingInput.bytesReadTotal
                        }
                        onProgress?.invoke(reported, progressTotal)
                        entry = tar.nextTarEntry
                    }
                    val completed = if (progressTotal > 0) progressTotal else countingInput.bytesReadTotal
                    onProgress?.invoke(completed, progressTotal)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось распаковать rootfs", e)
            throw e
        }
    }

    private fun findBundledRootfsAsset(): String? {
        for (asset in BUNDLED_ROOTFS_ASSETS) {
            try {
                context.assets.open(asset).use { return asset }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun getBundledRootfsLength(assetPath: String): Long? {
        return try {
            context.assets.openFd(assetPath).use { it.length }
        } catch (_: Exception) {
            null
        }
    }

    private fun setupRootfs(rootfs: File) {
        try {
            val resolvConf = File(rootfs, "etc/resolv.conf")
            resolvConf.parentFile?.mkdirs()
            resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")

            val ubuntuSources = File(rootfs, "etc/apt/sources.list.d/ubuntu.sources")
            ubuntuSources.parentFile?.mkdirs()
            ubuntuSources.writeText(
                """
                Types: deb
                URIs: http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/
                Suites: noble noble-updates noble-backports
                Components: main universe restricted multiverse
                Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg

                Types: deb
                URIs: http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/
                Suites: noble-security
                Components: main universe restricted multiverse
                Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
                """.trimIndent() + "\n"
            )

            listOf("dev", "proc", "sys", "tmp", "root", "home").forEach {
                File(rootfs, it).mkdirs()
            }
            File(rootfs, "data/data/${context.packageName}").mkdirs()

            if (AndroidShellExecutor.isRootAvailable()) {
                context.filesDir.apply {
                    setReadable(true, false)
                    setExecutable(true, false)
                }
                rootfs.apply {
                    setReadable(true, false)
                    setExecutable(true, false)
                }
                listOf("tmp", "root", "usr", "bin", "etc", "lib").forEach {
                    File(rootfs, it).apply {
                        setReadable(true, false)
                        setExecutable(true, false)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось завершить настройку rootfs", e)
        }
    }

    suspend fun uninstall(): Boolean = withContext(Dispatchers.IO) {
        try {
            val rootfs = getRootfsDir()
            if (rootfs.isDirectory()) rootfs.deleteRecursively()
            File(context.cacheDir, ROOTFS_ARCHIVE).delete()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось удалить rootfs", e)
            false
        }
    }
}
