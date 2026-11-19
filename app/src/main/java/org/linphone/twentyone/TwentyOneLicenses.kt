/*
 * fork: licence aplikace a knihoven v APK (assets/licenses.txt,
 * generuje scripts/gen-licenses.py). Otevírá se z Nápovědy.
 */
package org.linphone.twentyone

import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.text.TextUtils
import android.webkit.WebView
import org.linphone.R

object TwentyOneLicenses {

    private const val ASSET = "licenses.txt"

    fun show(context: Context) {
        val text = try {
            context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            return
        }
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val fg = if (night) "#e6e6e6" else "#1a1a1a"
        val bg = if (night) "#1e1e1e" else "#ffffff"
        val html = "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<style>body{margin:12px;background:$bg;color:$fg}" +
            "pre{white-space:pre-wrap;word-wrap:break-word;font-size:11px;line-height:1.35}</style>" +
            "</head><body><pre>" + TextUtils.htmlEncode(text) + "</pre></body></html>"
        val web = WebView(context).apply {
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            setBackgroundColor(android.graphics.Color.parseColor(bg))
            loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.twentyone_licenses_title)
            .setView(web)
            .setPositiveButton(R.string.twentyone_licenses_close, null)
            .show()
    }
}
