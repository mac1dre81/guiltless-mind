package com.document.editor

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object AppDiagnostics {
    private const val TAG = "DocEditorDiagnostics"
    private const val CRASH_FILE_NAME = "last_crash_report.txt"
    private const val BREADCRUMB_FILE_NAME = "runtime_breadcrumbs.log"
    private const val MAX_LOG_BYTES = 128 * 1024L

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            appendLine(
                appContext,
                CRASH_FILE_NAME,
                buildString {
                    append(timestamp())
                    append(" | thread=")
                    append(thread.name)
                    append(" | uncaught=")
                    append(throwable::class.java.name)
                    append(": ")
                    append(throwable.message.orEmpty())
                    append('\n')
                    append(Log.getStackTraceString(throwable))
                }
            )
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    fun logBreadcrumb(context: Context, message: String, throwable: Throwable? = null) {
        val line = buildString {
            append(timestamp())
            append(" | ")
            append(message)
            if (throwable != null) {
                append(" | ")
                append(throwable::class.java.simpleName)
                append(": ")
                append(throwable.message.orEmpty())
            }
        }
        appendLine(context.applicationContext, BREADCRUMB_FILE_NAME, line)
        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else {
            Log.d(TAG, message)
        }
    }

    fun consumeLastCrashReport(context: Context): String? {
        val file = File(context.applicationContext.filesDir, CRASH_FILE_NAME)
        if (!file.exists()) {
            return null
        }
        val report = runCatching { file.readText() }.getOrNull()
        runCatching { file.delete() }
        return report?.takeIf { it.isNotBlank() }
    }

    fun describeUri(uriString: String): String {
        return runCatching {
            val uri = uriString.toUri()
            val name = uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.takeIf { it.isNotBlank() }
                ?: "document"
            "${uri.scheme ?: "file"}://$name"
        }.getOrDefault("invalid-uri")
    }

    private fun appendLine(context: Context, fileName: String, line: String) {
        runCatching {
            val file = File(context.filesDir, fileName)
            file.parentFile?.mkdirs()
            file.appendText(line + System.lineSeparator())
            trimIfNeeded(file)
        }
    }

    private fun trimIfNeeded(file: File) {
        if (!file.exists() || file.length() <= MAX_LOG_BYTES) {
            return
        }
        val text = file.readText()
        val trimmed = text.takeLast((MAX_LOG_BYTES / 2).toInt())
        file.writeText(trimmed)
    }

    private fun timestamp(): String {
        return TIMESTAMP_FORMAT.format(Date())
    }

    private val TIMESTAMP_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
}

