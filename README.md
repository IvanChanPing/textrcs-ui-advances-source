# Guide 4 — TextRCS UI advances, source map

This guide turns the UI work in the TextRCS reference build into a source-first handoff for a normal Android project. The matching Kotlin and XML files are under `UI Advances - Source Exact/`; they are copied from the current source owners rather than reconstructed from generated smali.

## Status and verification boundary

- The bundle is source-only. No APK or Android compilation was authorized for this handoff.
- File identity is verified with SHA-256 after export.
- The active TextRCS entry points were mapped from current Kotlin, generated call sites, project journals, and Git history.
- Runtime appearance, device behavior, and host-app compatibility remain unverified until the receiving app is compiled and exercised through its real UI.

## Pre-build risk pass

1. **Assumptions:** verified — TextRCS already owns the implementations listed below; this handoff reuses them. Unverified — another app will have the same view hierarchy, gallery contract, resource IDs, and event model.
2. **Feasibility:** the motion utilities are ordinary Kotlin/View code, but the IME, gallery, and typing adapters contain TextRCS/Textra-specific lookup logic. Those adapters must be mapped to the receiving app's real source owners rather than pasted blindly.
3. **Preconditions:** AndroidX window-insets support, a Material container-transform implementation for the photo route, a translucent destination theme for rounded activity corners, and real source-level access to the conversation and gallery screens.
4. **Entry points:** application startup, conversation open/close, attachment-panel open/close, quick-reply creation, reaction insertion, photo launch/destination/close, conversation focus, typing events, and delivered-message events are all listed below.
5. **Cross-cutting:** lifecycle cleanup, activity weak references, IME ownership, gallery gesture arbitration, accessibility motion settings, and the existing live-tuning/diagnostic transports must be reviewed in the host app.
6. **Observability:** the source-exact bundle preserves TextRCS's `RemoteConfig`/`Hooks` integration and box-readable `Imelog` transport. Replace environment-specific endpoints before shipping another product; do not reduce diagnostics to user-couriered logs.
7. **Verification reachability:** static identity and wiring can be checked here. Compile, install, rendered-frame review, and real click/gesture verification belong to the receiving app and are explicitly outside this handoff.

## What is included

The bundle carries the current active UI route:

- Rounded conversation-screen activity transition, dim, and under-screen parallax.
- Keyboard-synchronized conversation content and attachment-panel handoff.
- Quick-reply fade tuning and rounded clipping.
- Reaction-emoji pop animation.
- Source-photo to stock-gallery container transform, tap-time placeholder, page-aware return handling, and vertical drag-dismiss.
- Foreground-conversation typing indicator.
- The four activity animation XML resources and the status-row layout used by the reference build.
- The current live-tuning and auto-upload diagnostic helpers required by the source owners.

The bundle intentionally excludes obsolete or diagnostic-only branches:

- `ImageMorphViewer`, `SwipeImageGallery`, `ZoomImageView`, and `ZoomMorph` are the retired custom-overlay gallery route.
- `PopupTesterActivity`, `ReactionPopTestActivity`, and `QuickConvoTrace` are test/trace surfaces, not production UI owners.
- Pairing, messaging transport, spam, and wake behavior are outside this UI-only map.

## Source layout

Copy `UI Advances - Source Exact/src/main/` into the receiving app module, preserving package paths initially:

```text
src/main/java/com/textrcs/anim/
  ConvoCornerAnim.kt
  ImeSyncAnim.kt
  PopupAnimHooks.kt
  ReactionPop.kt
src/main/java/com/textrcs/control/
  Hooks.kt
  LiveTune.kt
  RemoteConfig.kt
src/main/java/com/textrcs/diag/
  Imelog.kt
src/main/java/com/textrcs/ui/
  DragDismissTouchListener.kt
  GalleryClose.kt
  GalleryMorph.kt
  MorphGalleryLauncher.kt
  QuickReplyCorners.kt
  TypingIndicator.kt
src/main/res/anim/
  textrcs_overlay_enter.xml
  textrcs_overlay_exit.xml
  textrcs_overlay_partial_enter.xml
  textrcs_overlay_partial_exit.xml
src/main/res/layout/
  convo_messagelist_row_status.xml
```

Also reuse these existing Integration Kit dependencies instead of duplicating them:

- `App Integration Source/com/textrcs/diag/ScreenTracer.kt`
- The app's existing AndroidX Core/View and Material dependencies.
- `Files to Add to Your App/res/values/styles-parallax-snippet.xml`, merged into the host theme rather than copied as a second theme authority.

## Public API and exact invocation map

| User-visible advance | Source owner | Source-level invocation |
|---|---|---|
| Rounded activity open/close with dim and under-screen parallax | `ConvoCornerAnim` | Once: `registerActivityTracking(application)`. Destination `onCreate`: `attach(activity)`. Before back/up finishes: `attachClose(activity)`. Emergency cleanup: `reset(activity)`. |
| Conversation content follows the keyboard; attachment panel can temporarily reclaim layout | `ImeSyncAnim` | Conversation `onCreate`: `attach(activity)`. Attachment panel open/close: `setPlusPanelOpen(true/false)`. Map its TextRCS view lookup to the host's message-list-plus-compose container and top bar. |
| Quick-reply fade uses a tuneable duration and explicit cubic easing | `PopupAnimHooks` | When constructing the popup animator: `duration = fadeMs()` and `interpolator = interpolator()`. Start its polling only if the host intentionally keeps the live-tuning layer. |
| Reaction emoji visibly pops when inserted | `ReactionPop` | After the reaction view is attached and laid out: `popEmoji(reactionView)` or `popEmoji(reactionView, delayMs)`. |
| Quick-reply card gets rounded visible bounds | `QuickReplyCorners` | After the quick-reply root is laid out: `apply(rootView)`. |
| Photo expands from the tapped pixels into the stock gallery | `MorphGalleryLauncher` + `GalleryMorph` | Source tap: `launch(tappedImageView, conversationId, messageId)`. Gallery `onCreate`: `GalleryMorph.onCreate(this)`. If the gallery exposes a reliable image-ready callback, call `onImageReady(this, photoView)`. |
| Gallery closes through the return transition | `GalleryClose` | Back/up/dismiss: `close(activity)`. A shared generic back runner may call `smartClose(activity)` to preserve plain finish behavior outside the gallery. |
| Vertical drag dismiss without breaking zoom or paging | `DragDismissTouchListener` | Installed by `GalleryMorph` on the active photo page; the host must supply or preserve the photo view's original touch delegate. |
| Typing feedback is scoped to the visible conversation | `TypingIndicator` | Window focus: `onConvoFocus(activity, hasFocus)`. Transport event: `onTypingEvent(conversationId, senderNumber, started)`. Incoming bubble: `onMessageDelivered(senderNumber)`. |

## Minimal source call sites

Application startup:

```kotlin
override fun onCreate() {
    super.onCreate()
    ConvoCornerAnim.registerActivityTracking(this)
    LiveTune.start(this) // Keep only when this app intentionally uses the supplied remote config.
}
```

Conversation screen:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.conversation)
    ConvoCornerAnim.attach(this)
    ImeSyncAnim.attach(this)
}

private fun closeConversation() {
    ConvoCornerAnim.attachClose(this)
    finish()
}

private fun onAttachmentPanelVisibilityChanged(open: Boolean) {
    ImeSyncAnim.setPlusPanelOpen(open)
}
```

Reaction insertion:

```kotlin
reactionView.doOnLayout {
    ReactionPop.popEmoji(reactionView)
}
```

Photo source and destination:

```kotlin
fun openPhoto(imageView: View, conversationId: Long, messageId: Long) {
    if (!MorphGalleryLauncher.launch(imageView, conversationId, messageId)) {
        openPhotoWithoutTransition(conversationId, messageId)
    }
}

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    GalleryMorph.onCreate(this)
    setContentView(R.layout.gallery)
}

private fun closePhoto() = GalleryClose.close(this)
```

Typing lifecycle:

```kotlin
override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    TypingIndicator.onConvoFocus(this, hasFocus)
}

fun onTypingEvent(conversationId: String, sender: String, started: Boolean) {
    TypingIndicator.onTypingEvent(conversationId, sender, started)
}

fun onIncomingBubbleCommitted(sender: String) {
    TypingIndicator.onMessageDelivered(sender)
}
```

## Host-app substitutions required before compilation

| TextRCS reference contract | Replace with the host app's source contract |
|---|---|
| `ConvoActivity` | The real conversation/detail activity. |
| `messageListAndSendArea` numeric lookup | A normal `R.id` or direct binding for the message list plus compose region. |
| `actionbarContainer` numeric lookup | The host's top toolbar/header binding used for clipping. |
| Textra's custom resize-suppression fields | Remove when the host has a single inset owner; otherwise route all competing resize paths through one explicit host interface. |
| Gallery class-name and `convoId`/`msgId` extras | The host's actual stock gallery activity and stable photo identity contract. |
| Reflection for PhotoView/ViewPager | Direct typed references when those classes are available in source. Preserve the current gesture arbitration rules. |
| Typing recipient reflection | The host conversation model's real participant list. Keep normalized-phone matching or replace it with the host's stable participant ID. |
| Box URLs in `LiveTune` and `Imelog` | The receiving project's approved config and box-readable diagnostic endpoints. |

## Motion and lifecycle invariants

- All motion uses explicit time-based animators and cubic easing; do not introduce physics-based animation defaults.
- The rounded destination window must be translucent with a transparent background or clipped corners reveal black pixels.
- The under-screen parallax and destination slide must use synchronized durations.
- The IME path must have one owner. Do not combine framework resize, a second app resize, and translation.
- The photo transition name belongs to the image, not the whole bubble.
- A tap-time bitmap placeholder starts the photo transform before the full decode; the existing image loader later replaces that same destination view.
- Drag-dismiss is enabled only at minimum zoom and for a mostly vertical, single-pointer gesture; horizontal paging and zoom/pan retain ownership.
- Gallery close must use the return-transition path only for the gallery; unrelated activities retain plain `finish()`.
- Long-lived activity references remain weak and callbacks must be cleared when the owning screen is destroyed.

## Verification checklist for the receiving app

1. Compile the host app and resolve every host substitution above without reflection when typed source is available.
2. Open and close a conversation through the real UI; inspect mid-transition corners, dim, and under-screen motion.
3. Open and close the keyboard; verify the list and compose bar move together with no resize jump or top-bar overdraw.
4. Open and close the attachment panel from both keyboard-visible and keyboard-hidden states.
5. Open quick reply, add a reaction, and verify the explicit tween motion with accessibility motion settings enabled and disabled.
6. Tap an image bubble, verify that only the photo grows, swipe pages, pinch zoom, drag vertically to dismiss, and use system back.
7. Trigger typing start/stop and an arriving message in the visible conversation; verify scoping and cleanup.
8. Read the app's automatically delivered diagnostics directly; never ask the tester to courier logs.

