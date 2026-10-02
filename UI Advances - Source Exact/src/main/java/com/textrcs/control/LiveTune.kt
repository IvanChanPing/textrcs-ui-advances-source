// [LIVE_TUNE] — lightweight config.json poller for LIVE-TUNING injected UI
// values (image-open morph, gallery, bubble/convo corners, reaction pop) on the
// real device with NO rebuild. Mirrors the incall-preview live-config pattern:
// a small daemon thread GETs a served JSON every ~1.5s and pushes it into
// [RemoteConfig], whose typed getters every hook site reads with a compile-time
// default — so the app behaves identically if the poll never succeeds (offline,
// bad URL, stripped build). Started once from RemoteControlProvider.onCreate.
//
// TUNE IT: edit the served file
//   /root/agent-work/projects/tracker-bridge/static/textra2-morph-config.json
// (→ https://204-168-163-118.sslip.io/trackers/static/textra2-morph-config.json)
// and the change lands on-device within ~1.5s, no reinstall. Every key +
// default is listed in that file.
package com.textrcs.control

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object LiveTune {

    private const val TAG = "textrcs-livetune"

    // Default served location (box Caddy static dir). Overridable at runtime
    // once a first config has landed, via the "live_tune_url" key.
    private const val DEFAULT_URL =
        "https://204-168-163-118.sslip.io/trackers/static/textra2-morph-config.json"
    private const val DEFAULT_POLL_MS = 1500L

    private val started = AtomicBoolean(false)
    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "textrcs-LiveTune").apply { isDaemon = true }
    }

    @Volatile private var version = 0L
    @Volatile private var lastCfg = ""

    /** Start the poll loop once (idempotent). Safe to call from any thread. */
    @JvmStatic
    fun start(ctx: Context) {
        if (!started.compareAndSet(false, true)) return
        exec.execute { loop() }
        Log.i(TAG, "LiveTune poller started")
        com.textrcs.diag.Imelog.post("livetune", "poller started")
    }

    private fun loop() {
        while (true) {
            val url = RemoteConfig.getString("live_tune_url", DEFAULT_URL)
            try {
                val json = fetch(url)
                if (json != null) {
                    version += 1
                    RemoteConfig.replace(json, version)
                    Log.i(TAG, "config v$version applied (${json.length()} keys)")
                    val cs = json.toString()
                    if (cs != lastCfg) { lastCfg = cs; com.textrcs.diag.Imelog.post("livetune", "config v$version applied (${json.length()} keys)") }
                }
            } catch (t: Throwable) {
                Log.i(TAG, "poll error: ${t.javaClass.simpleName}: ${t.message}")
            }
            val pollMs = RemoteConfig.getLong("live_tune_poll_ms", DEFAULT_POLL_MS)
            try { Thread.sleep(pollMs.coerceAtLeast(300L)) }
            catch (_: InterruptedException) { return }
        }
    }

    private fun fetch(url: String): JSONObject? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
        }
        try {
            val code = conn.responseCode
            if (code != 200) { Log.i(TAG, "HTTP $code from $url"); return null }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body)
        } finally {
            try { conn.disconnect() } catch (_: Throwable) {}
        }
    }
}
