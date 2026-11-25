package com.perflink.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Keeps the phone app up to date over the internet, on its own: no PC, USB or shared Wi-Fi needed.
 *
 * It asks GitHub for the latest PerfLink release; when that is newer than this app, it downloads
 * PerfLink.apk and installs it with Android's package installer. Android asks the user once
 * ("Install unknown apps" for PerfLink, then Update); after that, on Android 12 and newer, PerfLink
 * can update itself without asking, because it installed itself.
 */
object AppUpdater {
    private const val TAG = "PerfLinkUpdate"
    private const val REPO = "NirabhraKoley/PerfLink"
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    /** [summary]: one line about what's new, shown in the "Update available" pop-up. */
    data class Release(val version: String, val apkUrl: String, val summary: String = "")

    /** A newer release the user hasn't decided on yet: MainActivity shows the "Update available" pop-up. */
    val available = kotlinx.coroutines.flow.MutableStateFlow<Release?>(null)

    /** "Later" was tapped for this version: don't ask again until the app is opened next time. */
    @Volatile private var laterFor: String? = null

    fun later(rel: Release) {
        laterFor = rel.version
        available.value = null
    }

    /**
     * One line about the release for the pop-up: a hidden `<!-- summary: ... -->` line in the release notes,
     * else the notes' first line of text (headings, bullets and **bold** marks removed).
     */
    fun summaryOf(body: String?): String {
        val text = body ?: return ""
        Regex("""<!--\s*summary:\s*(.+?)\s*-->""", RegexOption.IGNORE_CASE).find(text)?.let { return it.groupValues[1].trim() }
        val line = text.lineSequence().map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("<!--") && !it.startsWith("```") } ?: return ""
        return line.trimStart('-', '*', ' ').replace("**", "").replace("`", "").replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
            .trim().let { if (it.length > 140) it.take(137).trimEnd() + "…" else it }
    }

    fun versionParts(v: String?): List<Int> = Regex("""\d+""").findAll(v ?: "").map { it.value.toInt() }.toList()

    /** True when [remote] is a later version than [local] ("1.10" > "1.9"). */
    fun isNewer(remote: String?, local: String?): Boolean {
        val a = versionParts(remote); val b = versionParts(local)
        if (a.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** GitHub's "latest release" JSON -> its version and the PerfLink.apk download link. */
    fun parseRelease(json: String): Release? = try {
        val o = JSONObject(json)
        val tag = o.optString("tag_name").trimStart('v', 'V')
        val assets = o.optJSONArray("assets")
        var url: String? = null
        if (assets != null) for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").equals("PerfLink.apk", ignoreCase = true)) url = a.optString("browser_download_url")
        }
        if (tag.isNotBlank() && !url.isNullOrBlank()) Release(tag, url, summaryOf(o.optString("body"))) else null
    } catch (e: Exception) {
        null
    }

    fun installedVersion(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"

    @Volatile private var busy = false

    /** Testing only: `--es update_api http://127.0.0.1:PORT/latest.json` checks a local test server instead of GitHub. */
    @Volatile var testApi: String? = null

    /**
     * Checks GitHub. A newer version isn't installed straight away: it goes to [available], and the app asks
     * first ("Update available" pop-up with a line about what's new: Update now / Later).
     */
    suspend fun checkAndUpdate(ctx: Context, manual: Boolean = false): String = withContext(Dispatchers.IO) {
        if (busy) return@withContext "already updating"
        val result = try {
            val app = ctx.applicationContext
            val local = installedVersion(app)
            val api = testApi ?: "https://api.github.com/repos/$REPO/releases/latest"
            val rel = http.newCall(Request.Builder().url(api)
                .header("User-Agent", "PerfLink-app").header("Accept", "application/vnd.github+json").build())
                .execute().use { r -> if (r.isSuccessful) parseRelease(r.body?.string() ?: "") else null }
                ?: return@withContext "couldn't check (offline?)".also { if (manual) toast(app, "Couldn't check for updates") }
            if (!isNewer(rel.version, local)) {
                if (manual) toast(app, "PerfLink $local is up to date")
                return@withContext "up to date ($local)"
            }
            if (!manual && laterFor == rel.version) return@withContext "update ${rel.version}: asked, later"
            available.value = rel
            "update ${rel.version} available: asking"
        } catch (e: Exception) {
            Log.w(TAG, "update check failed", e)
            "failed: ${e.message}"
        }
        Log.w(TAG, "update check: $result")   // (w: MIUI hides info lines from apps)
        result
    }

    /** "Update now": download the release and install it. */
    suspend fun install(ctx: Context, rel: Release): String = withContext(Dispatchers.IO) {
        if (busy) return@withContext "already updating"
        busy = true
        available.value = null
        val result = try {
            val app = ctx.applicationContext
            val local = installedVersion(app)
            toast(app, "Downloading PerfLink ${rel.version}…")
            val dir = File(app.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val apk = File(dir, "PerfLink-${rel.version}.apk")
            http.newCall(Request.Builder().url(rel.apkUrl).header("User-Agent", "PerfLink-app").build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext "download failed (${r.code})"
                apk.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
            }
            // only ever install PerfLink itself, and only a newer one (Android also checks the signature)
            val info = app.packageManager.getPackageArchiveInfo(apk.path, 0)
            if (info?.packageName != app.packageName || !isNewer(info.versionName, local)) {
                apk.delete()
                return@withContext "downloaded file isn't a newer PerfLink"
            }
            install(app, apk)
            "installing ${rel.version}"
        } catch (e: Exception) {
            Log.w(TAG, "update check failed", e)
            "failed: ${e.message}"
        } finally {
            busy = false
        }
        Log.w(TAG, "update check: $result")   // (w: MIUI hides info lines from apps)
        result
    }

    private fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            // Android 12+: no prompt when PerfLink is the app that installed itself
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, id, Intent(ctx, UpdateReceiver::class.java), flags)
            s.commit(pi.intentSender)
        }
    }

    fun toast(ctx: Context, text: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(ctx, text, Toast.LENGTH_LONG).show() }
    }
}

/** Results from Android's package installer, and "PerfLink was just updated" (to open it again). */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // the update closed PerfLink: open it again (some phones block this; then just tap the icon)
            try {
                ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Log.w("PerfLinkUpdate", "couldn't reopen after the update", e)
            }
            return
        }
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android wants the user to confirm (first update, or older Android): show its dialog
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { ctx.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> Log.w("PerfLinkUpdate", "updated")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w("PerfLinkUpdate", "install failed: $status $msg")
                AppUpdater.toast(ctx, "PerfLink update failed: ${msg ?: status}")
            }
        }
    }
}
