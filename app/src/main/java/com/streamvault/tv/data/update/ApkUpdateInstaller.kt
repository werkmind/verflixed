package com.streamvault.tv.data.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.streamvault.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Downloads the APK in-app and launches the system package installer.
 * Avoids Fire TV "Downloader" / browser redirect chains.
 */
class ApkUpdateInstaller(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build(),
) {
    data class Result(
        val file: File,
        val bytes: Long,
    )

    suspend fun download(context: Context, apkUrl: String, onProgress: (Float) -> Unit = {}): Result =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val out = File(dir, "Verflixed-update.apk")
            if (out.exists()) out.delete()

            val req = Request.Builder()
                .url(apkUrl)
                .header("User-Agent", "Verflixed/${BuildConfig.VERSION_NAME}")
                .header("Accept", "application/vnd.android.package-archive,*/*")
                .get()
                .build()

            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    error("Download fehlgeschlagen (HTTP ${resp.code})")
                }
                val body = resp.body ?: error("Leere Antwort")
                val total = body.contentLength().coerceAtLeast(0L)
                body.byteStream().use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var readTotal = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            readTotal += n
                            if (total > 0) {
                                val pct = ((readTotal * 100) / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct / 100f)
                                }
                            }
                        }
                        output.flush()
                    }
                }
                if (out.length() < 1_000_000L) {
                    out.delete()
                    error("Download unvollständig (${out.length()} Bytes)")
                }
                Result(out, out.length())
            }
        }

    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openUnknownSourcesSettings(activity: Activity) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}"),
                    ),
                )
            } else {
                activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            }
        } catch (_: Exception) {
            try {
                activity.startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(
                    activity,
                    "Bitte in den Fire-TV-Einstellungen „Apps aus unbekannten Quellen“ erlauben",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    fun install(activity: Activity, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }
}
