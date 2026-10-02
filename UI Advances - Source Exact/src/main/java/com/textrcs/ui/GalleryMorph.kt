package com.textrcs.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.transition.Transition
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * GalleryMorph — the RECEIVER half of the Route A image-open morph (pairs with
 * [MorphGalleryLauncher], the sender).
 *
 * Purpose: configures Textra's stock fullscreen gallery (`GalleryActivity`) so
 * the tapped thumbnail immediately morphs toward the fullscreen photo via a Material
 * `MaterialContainerTransform`, and plays the morph in reverse on dismiss. It does
 * NOT replace the gallery — the gallery's real ViewPager swipe + PhotoView pinch-zoom
 * are untouched; this only attaches the enter/return transition.
 *
 * Invocation: [MorphGalleryLauncher] supplies the tapped chat image as the source;
 * the GalleryActivity smali onCreate hook calls [onCreate] for the destination.
 *
 * Contract: the source and destination shared elements are the chat thumbnail and
 * current gallery ImageView. Both windows disable shared-element overlays, keeping
 * those views below `android.R.id.content` so Material can draw without the platform
 * invalid-ancestor crash. Gallery paging, zoom, back, and drag-dismiss remain owned
 * by their existing collaborators.
 *
 * Visual: a bounded snapshot of the tapped thumbnail is installed into Textra's
 * destination PhotoView as soon as its bounds exist, so expansion does not wait for
 * the larger decode. Textra's existing loader replaces that same ImageView with the
 * full-resolution drawable. Gallery chrome fades separately; return reverses the route.
 *
 * Verification: source/config contracts and bundled reflective APIs are statically
 * checked. APK compilation and Android UI pixels are UNVERIFIED in this change.
 *
 * THE CORRECTED CONFIG (this is the heart of the fix — see docs/IMAGE_MORPH_PLAN.md
 * and memory reference_android_container_transform_morph_canonical):
 *  - The same transitionName is set on the selected chat image and only on the loaded,
 *    laid-out gallery photo. `sharedElementsUseOverlay=false` on both Activities keeps
 *    the image endpoints in the normal hierarchy below the drawing root.
 *  - setScrimColor(TRANSPARENT) — removes the default 32%-black scrim that flashed as
 *    "black bars at the wrong time" mid-morph (user #2). Any black is then only the
 *    gallery's own letterbox around a portrait photo, settled at the end.
 *  - setFitMode(FIT_MODE_HEIGHT) — predictable fill, no mid-morph letterbox jump.
 *  - The 380 ms bounds clock, cubic-bezier(0.645, 0.045, 0.355, 1), and destination
 *    fade through 300/380 of the route copy Real Weather's map-card → fullscreen
 *    motion. Each value remains live-tunable through RemoteConfig.
 *
 * HOW IT IS INVOKED (smali, GalleryActivity):
 *  - [onCreate] is called at the TOP of `GalleryActivity.onCreate` (before/around
 *    setContentView): it postpones the enter transition and attaches the MCT as the
 *    window shared-element enter/return transition + the Material shared-element
 *    callback.
 *  - [scheduleFindAndStart] polls only until the first page's `BasePhotoView` is laid
 *    out, installs the captured thumbnail if the full drawable is not ready, and starts
 *    the transition. Capture failure retains the older loaded-drawable fallback.
 *
 * REFLECTION: the Material classes (`...transition.platform.MaterialContainerTransform`
 * and `...MaterialContainerTransformSharedElementCallback`) are bundled in the app but
 * NOT on the inject_src compile classpath, so everything here is reflective and guarded
 * — any failure logs and degrades to a plain (no-morph) gallery open, never a crash.
 *
 * HOW TO TEST (device — UI click path cannot be driven from the build host):
 *   tap a conversation image → EXPECT the thumbnail itself to remain visible and grow
 *   into the loaded gallery photo (no black loading gap), then shrink back on Back. Watch
 *   `adb logcat -s textrcs-morph`. STATUS: compile-targeted; UI click-path UNVERIFIED.
 */
object GalleryMorph {

    private const val TAG = MorphGalleryLauncher.TAG

    private const val MCT =
        "com.google.android.material.transition.platform.MaterialContainerTransform"
    private const val MCT_CALLBACK =
        "com.google.android.material.transition.platform.MaterialContainerTransformSharedElementCallback"

    private val MORPH_DURATION_MS: Long get() = com.textrcs.control.RemoteConfig.getLong("morph_duration_ms", 380L)
    private val POSTPONE_TIMEOUT_MS: Long get() = com.textrcs.control.RemoteConfig.getLong("morph_postpone_timeout_ms", 600L)

    /** True once the image endpoint has started the postponed transition, so the polling
     *  route and the optional direct image-ready hook don't double-fire. */
    @Volatile private var started = false

    /** Consumed sender snapshot. Once assigned to the destination ImageView, Textra's
     *  existing asynchronous request owns the later full-resolution replacement. */
    private var openingPlaceholder: Drawable? = null

    /** The ViewPager page index shown when the gallery opened. If the current page
     *  differs, the user has swiped away → Back should fade (we clear the original
     *  bubble's tag) rather than morph the swiped-to photo into the wrong bubble (R2). */
    private var startPosition = -1
    /** The photo view that currently carries the shared-element transitionName (moves
     *  to the visible page on each swipe so the return morph uses the right photo). */
    private var currentTagged: View? = null
    private var pageListenerWired = false

    /**
     * Call at the top of GalleryActivity.onCreate. Postpones the enter transition and
     * attaches the MaterialContainerTransform as the window shared-element enter +
     * return transition, plus the Material shared-element callback.
     */
    @JvmStatic
    fun onCreate(activity: Activity) {
        started = false
        startPosition = -1
        currentTagged = null
        pageListenerWired = false
        openingPlaceholder = MorphGalleryLauncher.takeOpeningPlaceholder()
        if (!com.textrcs.control.RemoteConfig.getBoolean("morph_enabled", true)) return
        try {
            // Wait only for the destination photo's bounds. Its initial pixels come
            // from the tapped thumbnail; the full-resolution load may finish later.
            activity.postponeEnterTransition()
            // Keep the destination image in the normal hierarchy so android.R.id.content
            // remains its valid MaterialContainerTransform drawing ancestor.
            try { activity.window.sharedElementsUseOverlay = false }
            catch (t: Throwable) { Log.i(TAG, "useOverlay=false failed: $t") }

            // The gallery chrome is not shared: bring it in after the photo has begun
            // expanding so toolbar/background pixels never replace the thumbnail.
            if (com.textrcs.control.RemoteConfig.getBoolean("morph_chrome_fade_enabled", true)) try {
                activity.window.enterTransition = android.transition.Fade().apply {
                    startDelay = com.textrcs.control.RemoteConfig.getLong("morph_chrome_fade_delay_ms", 240L)
                    duration = com.textrcs.control.RemoteConfig.getLong("morph_chrome_fade_dur_ms", 160L)
                }
            } catch (t: Throwable) { Log.i(TAG, "chrome fade setup failed: $t") }

            val callback = try {
                Class.forName(MCT_CALLBACK).getConstructor().newInstance()
                    as? android.app.SharedElementCallback
            } catch (t: Throwable) {
                Log.i(TAG, "MCT shared-element callback unavailable: $t"); null
            }
            if (callback != null) activity.setEnterSharedElementCallback(callback)

            val enter = buildTransform()
            val ret = buildTransform()
            if (enter != null) activity.window.sharedElementEnterTransition = enter
            if (ret != null) activity.window.sharedElementReturnTransition = ret

            Log.i(TAG, "onCreate: morph wired (callback=${callback != null} " +
                    "enter=${enter != null} return=${ret != null})")
            com.textrcs.diag.Imelog.post("morph", "gallery onCreate wired callback=${callback != null} enter=${enter != null} return=${ret != null}")

            // Find the gallery's photo view, tag it with the shared-element name, and
            // start the morph — by polling the view tree (the ViewPager builds its
            // first page a few frames after onCreate). Self-contained so we need only
            // ONE smali hook (this onCreate), not a second hook into Textra's obfuscated
            // image-load path. R3 safety: if no photo view appears before the timeout,
            // keep waiting at a lower polling rate after the threshold; starting with an
            // capture failure keeps waiting for Textra's real drawable as a fallback.
            scheduleFindAndStart(activity, System.currentTimeMillis())
        } catch (t: Throwable) {
            openingPlaceholder = null
            Log.e(TAG, "onCreate morph wiring failed -> plain gallery", t)
            try { activity.startPostponedEnterTransition() } catch (_: Throwable) {}
        }
    }

    /**
     * Optional direct hook: start once the destination has bounds and either its real
     * drawable or the captured thumbnail placeholder.
     */
    @JvmStatic
    fun onImageReady(activity: Activity, imageView: View?) {
        if (started) return
        if (prepareEndpoint(imageView)) {
            wireFirstPage(activity, imageView!!)
            forceStart(activity)
        } else {
            scheduleFindAndStart(activity, System.currentTimeMillis())
        }
    }

    private fun forceStart(activity: Activity) {
        if (started) return
        started = true
        try { activity.startPostponedEnterTransition() }
        catch (t: Throwable) { Log.i(TAG, "startPostponed failed: $t") }
    }

    /** Tag the first on-screen page, attach drag-dismiss to it, and install the
     *  ViewPager page listener so the tag + dismiss follow the user's swipes. */
    private fun wireFirstPage(activity: Activity, photoView: View) {
        try { photoView.transitionName = MorphGalleryLauncher.TRANSITION_NAME }
        catch (t: Throwable) { Log.i(TAG, "tag photo failed: $t") }
        currentTagged = photoView
        attachDismiss(activity, photoView)
        val vp = findViewPager(activity)
        if (vp != null) {
            startPosition = try { vp.javaClass.getMethod("getCurrentItem").invoke(vp) as Int }
                            catch (t: Throwable) { -1 }
            wirePageListener(activity, vp)
        }
        Log.i(TAG, "wired page=$startPosition photo=${photoView.javaClass.simpleName} -> start morph")
        com.textrcs.diag.Imelog.post("morph", "FOUND photo page=$startPosition -> morph starting")
    }

    /** Drag-down-to-dismiss (#4): set a delegating touch listener on a page's photo
     *  view. Normal touches (zoom/pan/tap) are forwarded to PhotoView's own attacher;
     *  only a vertical drag while at rest is hijacked for dismiss. */
    private fun attachDismiss(activity: Activity, photoView: View) {
        val attacher = try {
            photoView.javaClass.getMethod("getAttacher").invoke(photoView) as? View.OnTouchListener
        } catch (t: Throwable) { Log.i(TAG, "getAttacher failed (no drag-dismiss delegate): $t"); null }
        try {
            photoView.setOnTouchListener(
                DragDismissTouchListener(activity, photoView, attacher) { onDragDismiss(activity) }
            )
        } catch (t: Throwable) { Log.i(TAG, "attachDismiss failed: $t") }
    }

    /** Drag-dismiss release past threshold: the photo has already slid off, so close
     *  WITHOUT a shared-element morph (clear the tag → plain finish). */
    private fun onDragDismiss(activity: Activity) {
        MorphGalleryLauncher.clearTaggedBubble()
        try { activity.finish() } catch (t: Throwable) { Log.i(TAG, "finish failed: $t") }
    }

    private fun findViewPager(activity: Activity): View? {
        val root = activity.findViewById<View>(android.R.id.content) ?: return null
        val cls = try { Class.forName("androidx.viewpager.widget.ViewPager") }
                  catch (t: Throwable) { return null }
        return firstOfClass(root, cls)
    }

    private fun firstOfClass(v: View?, cls: Class<*>): View? {
        if (v != null && cls.isInstance(v)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount)
            firstOfClass(v.getChildAt(i), cls)?.let { return it }
        return null
    }

    /** Add an OnPageChangeListener to the androidx ViewPager (not on our compile
     *  classpath) via a reflection Proxy, so [onPageChanged] fires on every swipe. */
    private fun wirePageListener(activity: Activity, viewPager: View) {
        if (pageListenerWired) return
        try {
            val listenerCls = Class.forName("androidx.viewpager.widget.ViewPager\$OnPageChangeListener")
            val handler = InvocationHandler { _, method, args ->
                when (method.name) {
                    "onPageSelected" -> {
                        try { onPageChanged(activity, (args?.get(0) as? Int) ?: -1) } catch (t: Throwable) {}
                        null
                    }
                    else -> when (method.returnType) {
                        Boolean::class.javaPrimitiveType -> false
                        Int::class.javaPrimitiveType -> 0
                        else -> null
                    }
                }
            }
            val proxy = Proxy.newProxyInstance(listenerCls.classLoader, arrayOf(listenerCls), handler)
            viewPager.javaClass.getMethod("addOnPageChangeListener", listenerCls).invoke(viewPager, proxy)
            pageListenerWired = true
        } catch (t: Throwable) {
            Log.i(TAG, "wirePageListener failed (drag-dismiss only on the first page): $t")
        }
    }

    /** On swipe: move the shared-element tag + drag-dismiss to the now-visible page so
     *  the return morph uses the right photo (R2 gallery side); and once the user has
     *  left the opened page, clear the original bubble's tag so Back FADES instead of
     *  morphing the swiped-to photo into the wrong (originally-tapped) bubble (R2). */
    private fun onPageChanged(activity: Activity, position: Int) {
        val pv = findPhotoView(activity) ?: return
        val previous = currentTagged
        if (previous != null && previous !== pv) try { previous.transitionName = null } catch (_: Throwable) {}
        try { pv.transitionName = MorphGalleryLauncher.TRANSITION_NAME } catch (_: Throwable) {}
        currentTagged = pv
        attachDismiss(activity, pv)
        if (com.textrcs.control.RemoteConfig.getBoolean("morph_clear_tag_on_swipe", false) && startPosition >= 0 && position != startPosition) MorphGalleryLauncher.clearTaggedBubble()
        Log.i(TAG, "page -> $position (start=$startPosition)")
    }

    /** Poll for the laid-out on-screen PhotoView, seed it from the captured thumbnail
     *  when needed, then start. Without a capture, retain the loaded-photo fallback. */
    private fun scheduleFindAndStart(activity: Activity, startMs: Long) {
        val h = Handler(Looper.getMainLooper())
        h.post(object : Runnable {
            private var waitingLogged = false

            override fun run() {
                if (started) return
                val pv = findPhotoView(activity)
                if (pv != null && prepareEndpoint(pv)) {
                    wireFirstPage(activity, pv)
                    forceStart(activity)
                } else {
                    val elapsed = System.currentTimeMillis() - startMs
                    if (elapsed >= POSTPONE_TIMEOUT_MS && !waitingLogged) {
                        waitingLogged = true
                        Log.i(TAG, "photo endpoint not ready at threshold -> keep source visible")
                        com.textrcs.diag.Imelog.post("morph", "WAITING for photo bounds/loaded fallback; source remains visible")
                    }
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        val key = if (waitingLogged) "morph_waiting_poll_interval_ms" else "morph_poll_interval_ms"
                        val fallback = if (waitingLogged) 100L else 16L
                        h.postDelayed(this, com.textrcs.control.RemoteConfig.getLong(key, fallback).coerceAtLeast(16L))
                    } else {
                        openingPlaceholder = null
                    }
                }
            }
        })
    }

    /** The current page's photo view. The ViewPager keeps adjacent pages (offscreen
     *  limit 2) in memory, so several BasePhotoViews exist at once — we must pick the
     *  one actually ON SCREEN (the centered current page), NOT the first in tree order
     *  (which could be an adjacent/offscreen page and would make the morph land on the
     *  wrong photo). Prefer PhotoView-class views; pick the largest on-screen area. */
    private fun findPhotoView(activity: Activity): View? {
        val root = activity.findViewById<View>(android.R.id.content) ?: return null
        val all = ArrayList<android.widget.ImageView>()
        collectImages(root, all)
        val laidOut = all.filter { isEndpointLaidOut(it) }
        val photoViews = laidOut.filter { it.javaClass.name.contains(com.textrcs.control.RemoteConfig.getString("morph_receiver_photo_class_substr", "PhotoView")) }
        val pool = if (photoViews.isNotEmpty()) photoViews else laidOut.filter { it.drawable != null }
        val rect = android.graphics.Rect()
        var best: android.widget.ImageView? = null
        var bestArea = 0L
        for (iv in pool) {
            if (!iv.getGlobalVisibleRect(rect)) continue   // skip fully off-screen pages
            val area = rect.width().toLong() * rect.height().toLong()
            if (area > bestArea) { bestArea = area; best = iv }
        }
        return best ?: pool.firstOrNull()
    }

    /** Bounds are the only hard requirement when a captured thumbnail is available. */
    private fun isEndpointLaidOut(view: View?): Boolean =
        view is android.widget.ImageView && view.width > 0 && view.height > 0 && view.isLaidOut

    /** Preserve an already-loaded full drawable. Otherwise install the tap-time pixels
     *  into the same ImageView that Textra's in-flight Glide request will update. */
    private fun prepareEndpoint(view: View?): Boolean {
        if (!isEndpointLaidOut(view)) return false
        val imageView = view as android.widget.ImageView
        if (imageView.drawable != null) {
            openingPlaceholder = null
            return true
        }
        val placeholder = openingPlaceholder ?: return false
        return try {
            imageView.setImageDrawable(placeholder)
            openingPlaceholder = null
            Log.i(TAG, "installed tap-time thumbnail placeholder -> start morph")
            com.textrcs.diag.Imelog.post("morph", "PLACEHOLDER installed; morph starts before full decode")
            true
        } catch (t: Throwable) {
            Log.i(TAG, "thumbnail placeholder install failed; wait for full drawable: $t")
            false
        }
    }

    /** All descendant ImageViews, depth-first. */
    private fun collectImages(v: View?, out: MutableList<android.widget.ImageView>) {
        if (v is android.widget.ImageView) out.add(v)
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount)
            collectImages(v.getChildAt(i), out)
    }

    /** Build a MaterialContainerTransform configured for the photo-out-of-bubble look,
     *  by reflection (version-proof; Material not on compile classpath). Each setter is
     *  guarded so a missing method on some build degrades gracefully. */
    private fun buildTransform(): Transition? {
        return try {
            val cls = Class.forName(MCT)
            val t = cls.getConstructor().newInstance()
            val intT = Int::class.javaPrimitiveType!!
            fun call(name: String, type: Class<*>, arg: Any) {
                try { cls.getMethod(name, type).invoke(t, arg) }
                catch (e: Throwable) { Log.i(TAG, "MCT.$name unavailable: $e") }
            }
            fun constInt(field: String, fallback: Int): Int =
                try { cls.getField(field).getInt(null) } catch (_: Throwable) { fallback }

            // No scrim -> no black-bar flash (user #2).
            call("setScrimColor", intT, com.textrcs.control.RemoteConfig.getInt("morph_scrim_color", Color.TRANSPARENT))
            // Predictable fill, no mid-morph letterbox.
            call("setFitMode", intT, com.textrcs.control.RemoteConfig.getInt("morph_fit_mode", constInt("FIT_MODE_HEIGHT", 2)))
            // Real Weather keeps the authored destination at full size behind the
            // moving bounds and fades it in during the first 300 ms of its 380 ms route.
            call("setFadeMode", intT, com.textrcs.control.RemoteConfig.getInt("morph_fade_mode", constInt("FADE_MODE_IN", 0)))
            try {
                val thresholdsClass = Class.forName("$MCT\$ProgressThresholds")
                val fadeStart = com.textrcs.control.RemoteConfig.getDouble("morph_fade_start", 0.0).toFloat().coerceIn(0f, 1f)
                val fadeEnd = com.textrcs.control.RemoteConfig.getDouble("morph_fade_end", 300.0 / 380.0).toFloat().coerceIn(fadeStart, 1f)
                val thresholds = thresholdsClass.getConstructor(
                    Float::class.javaPrimitiveType!!,
                    Float::class.javaPrimitiveType!!
                ).newInstance(fadeStart, fadeEnd)
                call("setFadeProgressThresholds", thresholdsClass, thresholds)
            } catch (e: Throwable) {
                Log.i(TAG, "MCT fade thresholds unavailable: $e")
            }
            call("setDuration", Long::class.javaPrimitiveType!!, MORPH_DURATION_MS)
            call("setHoldAtEndEnabled", Boolean::class.javaPrimitiveType!!, true)
            try {
                (t as Transition).interpolator = PathInterpolator(
                    com.textrcs.control.RemoteConfig.getDouble("morph_ease_x1", 0.645).toFloat().coerceIn(0f, 1f),
                    com.textrcs.control.RemoteConfig.getDouble("morph_ease_y1", 0.045).toFloat(),
                    com.textrcs.control.RemoteConfig.getDouble("morph_ease_x2", 0.355).toFloat().coerceIn(0f, 1f),
                    com.textrcs.control.RemoteConfig.getDouble("morph_ease_y2", 1.0).toFloat()
                )
            } catch (e: Throwable) { Log.i(TAG, "interpolator failed; retaining Material default: $e") }
            try { (t as Transition).addTarget(MorphGalleryLauncher.TRANSITION_NAME) }
            catch (e: Throwable) { Log.i(TAG, "addTarget(photo name) failed: $e") }
            t as Transition
        } catch (e: Throwable) {
            Log.e(TAG, "buildTransform failed", e); null
        }
    }
}
