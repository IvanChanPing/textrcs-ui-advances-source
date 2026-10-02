package com.textrcs.ui

import android.app.Activity
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * DragDismissTouchListener — "drag the fullscreen photo down to dismiss" (#4).
 *
 * WHAT THE USER SEES: in the open photo viewer, when the photo is NOT zoomed, dragging
 * it down (or up) past ~1/4 screen slides it off and closes the gallery (returning to
 * the conversation). A short drag springs back. Pinch-zoom, pan-while-zoomed, and
 * left/right paging are unaffected.
 *
 * WHAT IT IS / HOW IT FITS: a `View.OnTouchListener` set on Textra's `BasePhotoView`
 * (the PhotoView in each gallery page). It implements the Stfalcon/PhotoView de-facto
 * arbitration model (no official Material component exists): it only hijacks a touch
 * gesture when (a) the photo is at rest (current scale == minimum scale, read via
 * PhotoView's public getScale()/getMinimumScale()) and (b) the gesture is a single-
 * finger, mostly-vertical drag past touch-slop. Everything else — pinch/zoom, pan
 * while zoomed, taps — is FORWARDED to PhotoView's own touch handler (its attacher,
 * which implements View.OnTouchListener), so the stock zoom/tap behaviour is intact.
 * Horizontal swipes are intercepted by the parent ViewPager before they reach here,
 * so paging is unaffected.
 *
 * WHO ATTACHES IT: [GalleryMorph] attaches one to the current page's photo view (and
 * re-attaches on page change via the ViewPager page listener), passing the PhotoView's
 * own attacher as [delegate] and a dismiss lambda (slide off-screen then finish).
 *
 * WHY DELEGATE INSTEAD OF SUBCLASS/REPARENT: the gallery's view classes are obfuscated
 * and instantiated by Textra; we can't subclass them or safely reparent the content
 * mid shared-element transition. Delegating to the existing attacher is the least
 * invasive integration. Reflection (getScale/getMinimumScale) is guarded — on any
 * failure we treat the photo as "at rest" so a drag still dismisses (fail-open).
 *
 * HOW TO TEST (device): open a photo, drag it down slowly → it should follow the
 * finger and, past ~1/4 screen, slide off and return to the conversation; a small drag
 * should snap back; pinch-zoom then pan should still work (no accidental dismiss);
 * left/right should still page. STATUS: compile-targeted; UI click-path UNVERIFIED.
 */
class DragDismissTouchListener(
    private val activity: Activity,
    private val photoView: View,
    private val delegate: View.OnTouchListener?,
    private val onDismiss: () -> Unit
) : View.OnTouchListener {

    private val slop = ViewConfiguration.get(activity).scaledTouchSlop
    private var downY = 0f
    private var downX = 0f
    private var dragging = false

    override fun onTouch(v: View, e: MotionEvent): Boolean {
        com.textrcs.diag.Imelog.post("trace", "DragDismiss.onTouch()")
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && e.pointerCount == 1) {
                    val dy = e.rawY - downY
                    val dx = e.rawX - downX
                    if (atRest() && abs(dy) > slop && abs(dy) > abs(dx)) {
                        dragging = true
                        downY = e.rawY            // re-anchor so there's no jump
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                if (dragging) {
                    photoView.translationY = e.rawY - downY
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    val dy = photoView.translationY
                    val threshold = (photoView.height.coerceAtLeast(1)) * com.textrcs.control.RemoteConfig.getDouble("morph_drag_dismiss_fraction", 0.25).toFloat()
                    if (abs(dy) > threshold) {
                        // Slide the photo off the way it was dragged, then dismiss.
                        val soFactor = com.textrcs.control.RemoteConfig.getDouble("morph_drag_slideoff_factor", 1.5).toFloat()
                        val target = if (dy > 0) (photoView.height * soFactor) else -(photoView.height * soFactor)
                        photoView.animate().translationY(target).setDuration(com.textrcs.control.RemoteConfig.getLong("morph_drag_dismiss_dur_ms", 180L))
                            .withEndAction { try { onDismiss() } catch (t: Throwable) {
                                Log.i(MorphGalleryLauncher.TAG, "dismiss action failed: $t") } }
                            .start()
                    } else {
                        photoView.animate().translationY(0f).setDuration(com.textrcs.control.RemoteConfig.getLong("morph_drag_snapback_dur_ms", 150L)).start()  // spring back
                    }
                    return true
                }
            }
        }
        return delegate?.onTouch(v, e) ?: false
    }

    /** Photo is "at rest" (not zoomed) when current scale ≈ minimum scale. Read via
     *  PhotoView's public reflectable getters; fail-open (treat as at rest) if absent. */
    private fun atRest(): Boolean = try {
        com.textrcs.diag.Imelog.post("trace", "DragDismiss.atRest()")
        val scale = photoView.javaClass.getMethod("getScale").invoke(photoView) as Float
        val min = photoView.javaClass.getMethod("getMinimumScale").invoke(photoView) as Float
        scale <= min * com.textrcs.control.RemoteConfig.getDouble("morph_drag_atrest_tol", 1.05).toFloat()
    } catch (t: Throwable) {
        true
    }
}
