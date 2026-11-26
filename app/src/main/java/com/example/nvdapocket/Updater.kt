package com.example.nvdapocket

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Looks for a newer build on the project's GitHub releases page. */
object Updater {
    @Suppress("DEPRECATION")
    fun currentBuild(a: Activity): Int = try { a.packageManager.getPackageInfo(a.packageName, 0).versionCode } catch (_: Exception) { 0 }

    fun check(a: Activity) {
        Toast.makeText(a, "Checking for updates...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val c = URL("https://api.github.com/repos/mahmoud123456cm/NVDA_remote/releases/latest").openConnection() as HttpURLConnection
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.connectTimeout = 10000; c.readTimeout = 10000
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                val n = j.getString("tag_name").removePrefix("build-").toIntOrNull() ?: 0
                val assets = j.optJSONArray("assets")
                val dl = if (assets != null && assets.length() > 0) assets.getJSONObject(0).getString("browser_download_url") else j.getString("html_url")
                a.runOnUiThread {
                    if (n > currentBuild(a)) {
                        Toast.makeText(a, "New build $n found. Opening the download...", Toast.LENGTH_SHORT).show()
                        a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(dl)))
                    } else Toast.makeText(a, "You are up to date (build ${currentBuild(a)})", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                a.runOnUiThread { Toast.makeText(a, "Update check failed: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }
}
