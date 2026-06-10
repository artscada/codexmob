package com.codex.android.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * 工作区文件管理工具。
 *
 * 提供文件操作功能：
 * - 导出到 Downloads / 自定义目录
 * - 系统分享
 * - 批量导出（支持进度回调）
 * - SAF 文件操作
 */
object WorkspaceFileManager {

    private const val TAG = "WorkspaceFileManager"

    data class FileItem(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long,
        val extension: String = name.substringAfterLast('.', "")
    )

    /**
     * 扫描工作区目录
     */
    fun scanDirectory(dir: File, recursive: Boolean = false): List<FileItem> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        return dir.listFiles()?.map { file ->
            FileItem(
                name = file.name,
                path = file.absolutePath,
                isDirectory = file.isDirectory,
                size = if (file.isDirectory) 0L else file.length(),
                lastModified = file.lastModified()
            )
        }?.sortedWith(compareByDescending<FileItem> { it.isDirectory }.thenBy { it.name })
            ?: emptyList()
    }

    /**
     * 获取目录总大小（递归）
     */
    fun getDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()

        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * 导出到 Downloads 目录
     */
    fun exportToDownloads(context: Context, sourceDir: File, targetName: String = "CodexWorkspace"): ExportResult {
        return try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            val exportDir = File(downloadsDir, targetName)
            var fileCount = 0

            if (sourceDir.exists()) {
                copyDirectory(sourceDir, exportDir)
                fileCount = exportDir.walkTopDown().count { it.isFile }
            }

            ExportResult(
                success = true,
                targetPath = exportDir.absolutePath,
                fileCount = fileCount,
                message = "已导出 $fileCount 个文件到 Downloads/CodexWorkspace"
            )
        } catch (e: Exception) {
            Log.e(TAG, "导出失败", e)
            ExportResult(false, "", 0, "导出失败: ${e.message}")
        }
    }

    /**
     * 通过 SAF 导出到用户选择的位置
     */
    fun exportToSaf(context: Context, sourceFile: File, targetUri: Uri): Boolean {
        return try {
            context.contentResolver.openOutputStream(targetUri)?.use { out ->
                if (sourceFile.isDirectory) {
                    // 不支持目录直接写入 SAF，需逐个文件处理
                    false
                } else {
                    sourceFile.inputStream().use { it.copyTo(out) }
                    true
                }
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "SAF 导出失败", e)
            false
        }
    }

    /**
     * 系统分享文件
     */
    fun shareFile(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val mimeType = getMimeType(file.name) ?: "*/*"

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(
                Intent.createChooser(intent, "分享: ${file.name}")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.e(TAG, "分享失败", e)
        }
    }

    /**
     * 分享多个文件
     */
    fun shareMultipleFiles(context: Context, files: List<File>) {
        try {
            val uris = files.mapNotNull { file ->
                try {
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                } catch (e: Exception) {
                    null
                }
            }

            if (uris.isEmpty()) return

            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(
                Intent.createChooser(intent, "分享 ${files.size} 个文件")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.e(TAG, "批量分享失败", e)
        }
    }

    /**
     * 删除文件或目录
     */
    fun deleteFile(file: File): Boolean {
        return try {
            if (file.isDirectory) {
                file.deleteRecursively()
            } else {
                file.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "删除失败", e)
            false
        }
    }

    /**
     * 重命名文件
     */
    fun renameFile(file: File, newName: String): Boolean {
        return try {
            val newFile = File(file.parentFile, newName)
            file.renameTo(newFile)
        } catch (e: Exception) {
            Log.e(TAG, "重命名失败", e)
            false
        }
    }

    /**
     * 保存内容到文件
     */
    fun writeFile(file: File, content: String): Boolean {
        return try {
            file.parentFile?.mkdirs()
            file.writeText(content)
            true
        } catch (e: Exception) {
            Log.e(TAG, "写入失败", e)
            false
        }
    }

    /**
     * 获取 MIME 类型
     */
    fun getMimeType(fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "kt", "kts" -> "text/plain"
            "py" -> "text/x-python"
            "js", "ts", "tsx", "jsx" -> "text/javascript"
            "html", "htm" -> "text/html"
            "css", "scss" -> "text/css"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "md" -> "text/markdown"
            "yml", "yaml" -> "application/x-yaml"
            "toml" -> "application/toml"
            "gradle" -> "text/plain"
            "sh" -> "application/x-sh"
            "go" -> "text/x-go"
            "rs" -> "text/x-rust"
            "java" -> "text/x-java"
            "swift" -> "text/x-swift"
            "dart" -> "text/x-dart"
            "sql" -> "text/x-sql"
            "env" -> "text/plain"
            "gitignore" -> "text/plain"
            "dockerfile" -> "text/plain"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        }
    }

    /**
     * 判断是否为文本文件
     */
    fun isTextFile(fileName: String): Boolean {
        val textExtensions = setOf(
            "kt", "kts", "py", "js", "ts", "tsx", "jsx", "html", "htm", "css",
            "json", "xml", "md", "yml", "yaml", "toml", "gradle", "sh", "go",
            "rs", "java", "swift", "dart", "sql", "env", "txt", "log", "csv",
            "gitignore", "dockerfile", "cfg", "conf", "ini", "properties"
        )
        return fileName.substringAfterLast('.', "").lowercase() in textExtensions
    }

    data class ExportResult(
        val success: Boolean,
        val targetPath: String,
        val fileCount: Int,
        val message: String
    )

    private fun copyDirectory(source: File, dest: File) {
        if (source.isDirectory) {
            if (!dest.exists()) dest.mkdirs()
            source.listFiles()?.forEach { child ->
                copyDirectory(child, File(dest, child.name))
            }
        } else {
            source.copyTo(dest, overwrite = true)
        }
    }
}
