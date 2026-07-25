package com.berke.ioniqscope.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Downloads an APK to private storage and hands it to the system installer. */
class ApkDownloader(private val appContext: Context) {

    suspend fun download(url: String, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(appContext.cacheDir, "updates").apply { mkdirs() }
            val target = File(dir, "IoniqScope-update.apk")
            val partial = File(dir, "IoniqScope-update.apk.part")

            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 120_000
                setRequestProperty("User-Agent", com.berke.ioniqscope.charging.Http.USER_AGENT)
                instanceFollowRedirects = true
            }

            try {
                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException("HTTP ${connection.responseCode}")
                }
                val total = connection.contentLengthLong

                // Written to a .part file first, so an interrupted download can never
                // be handed to the installer as if it were a whole APK.
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        var done = 0L
                        var lastPercent = -1
                        while (input.read(buffer).also { read = it } > 0) {
                            output.write(buffer, 0, read)
                            done += read
                            if (total > 0) {
                                val percent = ((done * 100) / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }

            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) {
                throw IllegalStateException("Could not finalise the downloaded file")
            }
            target
        }

    /**
     * Opens the system installer. Android always shows its own confirmation for a
     * sideloaded package — there is no way to install this silently, and the app
     * does not try to look as though it did.
     */
    fun install(file: File) {
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.updates",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
    }

    fun canInstallPackages(): Boolean = appContext.packageManager.canRequestPackageInstalls()
}
