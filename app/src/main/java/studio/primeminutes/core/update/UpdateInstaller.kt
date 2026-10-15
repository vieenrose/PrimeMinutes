package studio.primeminutes.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the release APK and hands it to the system package installer. Android does NOT allow a
 * normal (non-device-owner) app to install silently, so the user always confirms in the platform
 * installer dialog — this is the standard FOSS self-update path (cf. NewPipe / Aurora). Because CI
 * signs every release with the same keystore, it installs as an in-place update, not a conflict.
 */
object UpdateInstaller {

    /** Download [url] to app cache, reporting 0..1 progress, and return the finished APK file. */
    suspend fun download(context: Context, url: String, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val dest = File(dir, "primeminutes-update.apk")
            val tmp = File(dir, "primeminutes-update.apk.part")
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            check(conn.responseCode in 200..299) { "Update download HTTP ${conn.responseCode}" }
            val total = conn.contentLengthLong.takeIf { it > 0 }
            if (tmp.exists()) tmp.delete()    // clear a leftover .part from an aborted prior attempt
            var read = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total != null) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            // A clean early-close yields a short file that the installer would reject with a
            // confusing "invalid package" error; surface it as a download failure instead.
            check(total == null || read == total) { "Update download truncated ($read/$total)" }
            if (dest.exists()) dest.delete()
            check(tmp.renameTo(dest)) { "Could not finalize update download" }
            runCatching { verify(context, dest) }.onFailure { dest.delete(); throw it }
            onProgress(1f)
            dest
        }

    /**
     * The downloaded APK must be this app, newer, and signed by the installed app's key. The system
     * installer would refuse a foreign signature too, but only after the user confirmed, and with an
     * error that reads like a broken update; a different package would even install side by side.
     */
    private fun verify(context: Context, apk: File) {
        val pm = context.packageManager
        val flags = if (android.os.Build.VERSION.SDK_INT >= 28) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            else @Suppress("DEPRECATION") android.content.pm.PackageManager.GET_SIGNATURES
        val got = pm.getPackageArchiveInfo(apk.path, flags) ?: error("Update is not a valid APK")
        check(got.packageName == context.packageName) { "Update is for ${got.packageName}, not ${context.packageName}" }
        val mine = pm.getPackageInfo(context.packageName, flags)
        check(androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(got) > androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(mine)) {
            "Update is not newer than the installed version"
        }
        fun certs(i: android.content.pm.PackageInfo): Set<String> =
            (if (android.os.Build.VERSION.SDK_INT >= 28) i.signingInfo?.apkContentsSigners?.toList()
             else @Suppress("DEPRECATION") i.signatures?.toList()).orEmpty().map { it.toCharsString() }.toSet()
        check(certs(got).isNotEmpty() && certs(got) == certs(mine)) { "Update is not signed by this app's key" }
    }

    /**
     * Launch the system installer for [apk]. If the user hasn't granted "install unknown apps" to
     * Prime Minutes yet, sends them to that settings screen instead (they grant, then tap Update again —
     * the APK is already cached, so it installs immediately). Returns true if the installer was
     * launched, false if routed to settings first.
     */
    fun install(context: Context, apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(settings) }
            return false
        }
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
    }
}
