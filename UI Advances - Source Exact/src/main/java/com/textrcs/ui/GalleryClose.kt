// [MORPH] Hookable + instrumented gallery close. The stock GalleryActivity close
// call site (GalleryActivity.smali) is redirected here so (a) whether the close runs
// the shared-element RETURN morph is live-tunable (morph_return_enabled), and (b) we
// log the full close state to imelog so the return-morph failure is readable:
//   curl -s https://204-168-163-118.sslip.io/imelog/ | grep textra2-morph | grep close
package com.textrcs.ui

import android.app.Activity
import android.view.View

object GalleryClose {
    @JvmStatic
    fun close(activity: Activity) {
        val re = com.textrcs.control.RemoteConfig.getBoolean("morph_return_enabled", true)
        val contentTag = try {
            activity.findViewById<View>(android.R.id.content)?.transitionName
        } catch (t: Throwable) { "err:${t.javaClass.simpleName}" }
        com.textrcs.diag.Imelog.post(
            "close",
            "close() called return_enabled=$re contentTag=$contentTag"
        )
        try {
            if (re) {
                activity.finishAfterTransition()
                com.textrcs.diag.Imelog.post("close", "finishAfterTransition() invoked")
            } else {
                activity.finish()
                com.textrcs.diag.Imelog.post("close", "finish() invoked")
            }
        } catch (t: Throwable) {
            com.textrcs.diag.Imelog.post("close", "close EXC ${t.javaClass.simpleName}: ${t.message}")
            try { activity.finish() } catch (_: Throwable) {}
        }
    }
    /** [diag] log which tag/class the shared back-Runnable A2/p runs (finds the gallery's back). */
    @JvmStatic
    fun traceBack(tag: Int, obj: Any?) {
        try { com.textrcs.diag.Imelog.post("aback", "run tag=$tag class=${obj?.javaClass?.name}") } catch (_: Throwable) {}
    }

    /** Class-checked close for the shared A2/p back Runnable: the GalleryActivity gets
     *  finishAfterTransition (so the shared-element RETURN morph fires); every other
     *  activity keeps plain finish(). morph_return_enabled=false forces plain finish. */
    @JvmStatic
    fun smartClose(activity: android.app.Activity) {
        try {
            val isGallery = activity.javaClass.name.contains("GalleryActivity")
            com.textrcs.diag.Imelog.post("aback", "smartClose ${activity.javaClass.simpleName} isGallery=$isGallery")
            if (isGallery && com.textrcs.control.RemoteConfig.getBoolean("morph_return_enabled", true))
                activity.finishAfterTransition()
            else activity.finish()
        } catch (t: Throwable) { try { activity.finish() } catch (_: Throwable) {} }
    }
}
