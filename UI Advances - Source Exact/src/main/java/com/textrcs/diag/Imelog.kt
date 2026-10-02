// [MORPH-DIAG] Fire-and-forget diagnostics POST to the box imelog collector, so the
// developer can read on-device morph / live-tune events THEMSELVES (curl the sink)
// instead of the user couriering logcat. Bounded + best-effort: a single daemon
// thread, short strings, never throws or blocks the UI. Read it with:
//   curl -s https://204-168-163-118.sslip.io/imelog/ | grep textra2-morph
package com.textrcs.diag

import android.os.Build
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object Imelog {

    private const val EP_BASE = "https://204-168-163-118.sslip.io/imelog/"

    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "textrcs-imelog").apply { isDaemon = true }
    }

    @JvmStatic
    fun post(tag: String, msg: String) = postTo("textra2-morph", tag, msg)

    /**
     * Post into an explicit collector namespace (…/imelog/<ns>) so one feature's
     * trace can be read on its own instead of interleaved with the morph spam.
     * Wake trace: curl -s https://204-168-163-118.sslip.io/imelog/ | grep textra2-wake
     */
    @JvmStatic
    fun postTo(ns: String, tag: String, msg: String) {
        exec.execute {
            try {
                val c = URL(EP_BASE + ns).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.setRequestProperty(
                    "X-Device",
                    "${Build.MANUFACTURER}/${Build.MODEL}/A${Build.VERSION.SDK_INT}"
                )
                c.outputStream.use { it.write("[$tag] $msg".toByteArray()) }
                c.inputStream.use { it.read() }
                c.disconnect()
            } catch (t: Throwable) {
                Log.i("textrcs-imelog", "post fail: ${t.message}")
            }
        }
    }
}
