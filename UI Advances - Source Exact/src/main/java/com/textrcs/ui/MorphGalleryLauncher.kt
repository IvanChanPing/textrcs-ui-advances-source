package com.textrcs.ui

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView

/**
 * MorphGalleryLauncher — the SENDER half of the Route A image-open morph.
 *
 * WHAT THE USER SEES: tapping an image attachment in a conversation makes that
 * thumbnail lift out of its chat bubble and grow (Material container-transform)
 * into Textra's OWN fullscreen gallery (the stock `GalleryActivity`, with its real
 * swipe + pinch-zoom). The conversation/bubble stays in place underneath and is
 * covered as the photo fills the screen — i.e. the photo expands OUT of the bubble,
 * the bubble does NOT move. (Route B, which morphed the whole bubble into a custom
 * overlay, is preserved on branch route-b-custom-gallery; this replaces it.)
 *
 * WHAT IT IS / HOW IT FITS: this is the "sender" side of a shared-element ACTIVITY
 * transition. It (1) finds the thumbnail ImageView inside the tapped bubble,
 * (2) tags it with [TRANSITION_NAME] so the framework can pair it with the matching
 * fullscreen image in the gallery, (3) builds the same Intent the stock code builds
 * (extras "convoId"/"msgId" — msgId selects WHICH image opens, so the correct image
 * is shown), and (4) starts the gallery with an
 * ActivityOptions.makeSceneTransitionAnimation bundle so the framework runs the
 * MaterialContainerTransform. The RECEIVER half (building + attaching the MCT, the
 * postpone/start, the return morph) is [GalleryMorph], invoked from
 * GalleryActivity.onCreate.
 *
 * HOW IT IS INVOKED: a smali hook in `com/mplus/lib/v6/K->e(...)Z` (the conversation
 * gesture handler, image-tap branch) replaces the stock `j4/a.c(intent)` launch with
 * a call to [launch], passing the host Activity (`G5/a->c`, an `x5/l` which extends
 * FragmentActivity), the tapped BubbleView, and the convoId/msgId it already has in
 * hand. Returning true means "handled" (skip the stock launch); false means we could
 * not launch (no Activity) and the smali falls back to the original stock path, so
 * this can never break opening an image.
 *
 * WHY a separate launcher (not the stock `j4/a.c`): `j4/a.c` defers the start via a
 * Runnable and takes NO ActivityOptions, so it cannot carry a shared-element bundle.
 * Material's classes aren't on the inject_src compile classpath, but the SENDER side
 * needs none of them — only framework ActivityOptions + View.setTransitionName — so
 * no reflection is needed here (the receiver [GalleryMorph] does the reflection).
 *
 * RISKS (see docs/IMAGE_MORPH_PLAN.md): R1 animator_duration_scale=0 makes the morph
 * snap (device setting; user confirmed it's ON — we still log the scale here for
 * observability); R2 after swiping in the gallery the bubble that should receive the
 * RETURN morph may be a different/recycled view (handled best-effort by the gallery's
 * shared-element callbacks; v1 is reliable for the originally-tapped image).
 *
 * HOW TO TEST (device — the UI click path cannot be driven from the build host):
 *   open a conversation with image attachments → tap one → EXPECT the thumbnail to
 *   morph/grow into the fullscreen gallery showing THAT image (not a different one),
 *   the bubble staying put underneath; swipe/zoom in the stock gallery; back returns
 *   to the conversation. Watch `adb logcat -s textrcs-morph` for the animator scale
 *   and the start path taken. STATUS: compile-targeted; UI click-path UNVERIFIED.
 */
object MorphGalleryLauncher {

    const val TAG = "textrcs-morph"

    /** Shared-element transition name; must match the one [GalleryMorph] sets on the
     *  fullscreen image. A single app-wide constant (only one morph runs at a time). */
    const val TRANSITION_NAME = "textrcs_img_morph"

    /** Weak handle to the bubble image view we tagged for the current morph, so the
     *  gallery (a different Activity) can CLEAR its transitionName once the user swipes
     *  to a different photo — otherwise Back would morph the swiped-to photo back into
     *  the WRONG (originally-tapped) bubble. Cleared → no shared-element match → clean
     *  fade return (R2). Weak so it can't leak the conversation view. */
    @Volatile
    private var taggedBubble: java.lang.ref.WeakReference<View>? = null

    /** Bounded copy of the pixels visible at tap time. The destination consumes this
     *  in the same process and uses it only until Textra's existing loader supplies
     *  the full-resolution drawable; it is never placed in the Intent. */
    @Volatile
    private var openingPlaceholder: BitmapDrawable? = null

    @JvmStatic
    @Synchronized
    fun takeOpeningPlaceholder(): BitmapDrawable? {
        val placeholder = openingPlaceholder
        openingPlaceholder = null
        return placeholder
    }

    /** Drop the shared-element tag from the originally-tapped bubble (called by
     *  [GalleryMorph] when the user has paged away from the opened image). */
    @JvmStatic
    fun clearTaggedBubble() {
        try { taggedBubble?.get()?.transitionName = null } catch (_: Throwable) {}
        taggedBubble = null
    }

    private const val GALLERY_CLASS = "com.mplus.lib.ui.convo.gallery.GalleryActivity"

    /**
     * Launch the stock gallery with a shared-element morph from the tapped bubble.
     *
     * @param bubble the tapped chat bubble View; its `context` is unwrapped to the
     *   host Activity (the conversation's `x5/l`), and we locate the thumbnail
     *   ImageView inside it to use as the shared element. If the context does not
     *   resolve to an Activity we return false so the caller falls back to its stock
     *   launch. If no image view is found we still open the gallery, just without the
     *   morph. (Context is taken from the bubble — not a separate arg — so the smali
     *   call stays within the 5-register limit of non-range invoke-static.)
     * @param convoId Textra-internal conversation id (extra "convoId").
     * @param msgId   the tapped message id (extra "msgId") — selects which image opens.
     * @return true if we started the gallery (with or without morph); false if we
     *   could not (no Activity), so the caller should fall back to its stock launch.
     */
    @JvmStatic
    fun launch(bubble: View?, convoId: Long, msgId: Long): Boolean {
        return try {
            openingPlaceholder = null
            if (!com.textrcs.control.RemoteConfig.getBoolean("morph_enabled", true)) { com.textrcs.diag.Imelog.post("morph", "DISABLED via morph_enabled=false -> stock"); return false }
            val activity = findActivity(bubble?.context)
            if (activity == null) {
                Log.i(TAG, "launch: no host Activity (bubble=${bubble != null}) -> stock launch")
                return false
            }

            // R1 observability: a scale of 0 (Remove animations / battery saver) makes
            // the morph snap instantly regardless of this code.
            val scale = try {
                Settings.Global.getFloat(
                    activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
                )
            } catch (t: Throwable) { -1f }

            val intent = Intent()
                .setClassName(activity, GALLERY_CLASS)
                .putExtra("convoId", convoId)
                .putExtra("msgId", msgId)

            val shared = bubble?.let { findStartImage(it) }
            if (shared != null) {
                openingPlaceholder = captureVisiblePixels(shared)
                shared.transitionName = TRANSITION_NAME
                taggedBubble = java.lang.ref.WeakReference(shared)
                // Keep the thumbnail inside the conversation hierarchy while the
                // destination photo is prepared. The gallery applies the same setting;
                // together they let the image itself be the two Activity endpoints.
                try { activity.window.sharedElementsUseOverlay = false }
                catch (t: Throwable) { Log.i(TAG, "useOverlay=false failed: $t") }
                try {
                    val cb = Class.forName("com.google.android.material.transition.platform.MaterialContainerTransformSharedElementCallback").getConstructor().newInstance() as? android.app.SharedElementCallback
                    if (cb != null) activity.setExitSharedElementCallback(cb)
                } catch (t: Throwable) { Log.i(TAG, "exit callback: $t") }
                val opts = ActivityOptions.makeSceneTransitionAnimation(
                    activity, shared, TRANSITION_NAME
                )
                Log.i(TAG, "launch: morph convoId=$convoId msgId=$msgId scale=$scale " +
                        "shared=${shared.javaClass.simpleName} placeholder=${openingPlaceholder != null}")
                com.textrcs.diag.Imelog.post("morph", "launch MORPH convo=$convoId msg=$msgId scale=$scale shared=${shared.javaClass.simpleName} placeholder=${openingPlaceholder != null}")
                activity.startActivity(intent, opts.toBundle())
            } else {
                // No image view found in the bubble — open the gallery anyway (correct
                // image still shown via msgId), just without the morph.
                Log.i(TAG, "launch: no shared image view -> plain open convoId=$convoId " +
                        "msgId=$msgId scale=$scale")
                run {
                    val sb = StringBuilder("launch PLAIN convo=$convoId msg=$msgId scale=$scale bubble=${bubble?.javaClass?.simpleName} tree:\n")
                    dumpTree(bubble, 0, sb)
                    com.textrcs.diag.Imelog.post("morph", sb.toString())
                }
                activity.startActivity(intent)
            }
            true
        } catch (t: Throwable) {
            openingPlaceholder = null
            Log.e(TAG, "launch failed -> defer to stock launch", t)
            false
        }
    }

    /** Snapshot the exact rendered thumbnail/bubble pixels so the fullscreen endpoint
     *  can begin moving before the larger image decode completes. The longest edge is
     *  capped by live config; allocation/draw failure simply keeps the load-gated path. */
    private fun captureVisiblePixels(view: View): BitmapDrawable? {
        if (view.width <= 0 || view.height <= 0) return null
        return try {
            val maxEdge = com.textrcs.control.RemoteConfig
                .getInt("morph_snapshot_max_edge_px", 1080).coerceAtLeast(1)
            val scale = minOf(1f, maxEdge.toFloat() / maxOf(view.width, view.height))
            val width = (view.width * scale).toInt().coerceAtLeast(1)
            val height = (view.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                scale(scale, scale)
                view.draw(this)
            }
            BitmapDrawable(view.resources, bitmap)
        } catch (t: Throwable) {
            Log.i(TAG, "thumbnail snapshot unavailable; retain load-gated start: $t")
            null
        }
    }

    /** The morph START view: the thumbnail ImageView (R.id.thumbnailImage) if present,
     *  else the first descendant ImageView, else null (no morph). Mirrors
     *  ImageMorphViewer.findStartView but returns null rather than the bubble itself,
     *  because morphing the bubble container (not the image) is exactly the "whole
     *  bubble moves" bug we are fixing — the shared element must be the IMAGE. */
    /** Config-driven source-view selection (live-tunable, no rebuild). Strategy key
     *  `morph_source_strategy`: auto | bubble | id | class | first_image | largest_drawable.
     *  Textra draws the message image inside BubbleView itself (no child ImageView), so
     *  `auto` degrades to the bubble bounds and a morph still fires; switch strategy from
     *  config to target a specific view once the tree dump shows what holds the image. */
    private fun findStartImage(bubble: View): View? {
        val rc = com.textrcs.control.RemoteConfig
        val idName = rc.getString("morph_source_id", "thumbnailImage")
        val classSub = rc.getString("morph_source_class_substr", "PhotoView")
        when (rc.getString("morph_source_strategy", "auto")) {
            "bubble" -> return bubble
            "id" -> return idLookup(bubble, idName) ?: bubble
            "class" -> return firstByClass(bubble, classSub) ?: bubble
            "first_image" -> return firstImageView(bubble) ?: bubble
            "largest_drawable" -> return largestDrawableLeaf(bubble) ?: bubble
        }
        // auto: precise options first, then degrade to the bubble so a morph still fires.
        idLookup(bubble, idName)?.let { return it }
        firstByClass(bubble, "PhotoView")?.let { return it }
        firstByClass(bubble, "ImageView")?.let { return it }
        largestDrawableLeaf(bubble)?.let { return it }
        return if (rc.getBoolean("morph_source_bubble_fallback", true)) bubble else null
    }

    private fun idLookup(bubble: View, idName: String): View? {
        if (idName.isBlank()) return null
        val ctx = bubble.context
        val id = ctx.resources.getIdentifier(idName, "id", ctx.packageName)
        return if (id != 0) bubble.findViewById(id) else null
    }

    private fun firstByClass(v: View?, substr: String): View? {
        if (v == null || substr.isBlank()) return null
        if (v.javaClass.name.contains(substr, ignoreCase = true) && v.width > 0 && v.height > 0) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) firstByClass(v.getChildAt(i), substr)?.let { return it }
        return null
    }

    /** Largest on-screen view carrying a drawable (ImageView w/ drawable, or any view
     *  with a background) — catches images Textra paints without an ImageView. */
    private fun largestDrawableLeaf(root: View?): View? {
        if (root == null) return null
        val out = ArrayList<View>()
        fun walk(v: View?) {
            if (v == null) return
            if (((v is ImageView && v.drawable != null) || v.background != null) && v.width > 0 && v.height > 0) out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        val rect = android.graphics.Rect()
        return out.filter { it.getGlobalVisibleRect(rect) }
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
    }

    private fun firstImageView(v: View?): ImageView? {
        if (v is ImageView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount)
            firstImageView(v.getChildAt(i))?.let { return it }
        return null
    }

    /** Diagnostic: dump the tapped bubble's view tree so we can see which view
     *  actually holds the image (Textra may draw it via a non-ImageView). */
    private fun dumpTree(v: View?, depth: Int, sb: StringBuilder) {
        if (v == null || depth > 7) return
        val id = try { if (v.id != View.NO_ID) v.resources.getResourceEntryName(v.id) else "-" } catch (t: Throwable) { "?" }
        val img = v is ImageView
        val drw = if (img) ((v as ImageView).drawable != null) else false
        sb.append("  ".repeat(depth)).append(v.javaClass.simpleName).append("#").append(id)
            .append(" ").append(v.width).append("x").append(v.height)
            .append(if (v.background != null) " bg" else "")
            .append(if (img) " IMG(drw=$drw)" else "").append("\n")
        if (v is ViewGroup) for (i in 0 until v.childCount) dumpTree(v.getChildAt(i), depth + 1, sb)
    }

    private fun findActivity(context: Context?): Activity? {
        var c: Context? = context
        while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
        return null
    }
}
