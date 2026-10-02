# TextRCS UI Advances

Reusable Android View source for conversation motion, keyboard interaction, quick reply, reactions, photo galleries, typing indicators, live tuning, and diagnostics.

## Features

- Rounded conversation-screen activity transition, dim, and under-screen parallax.
- Keyboard-synchronized conversation content and attachment-panel transitions.
- Quick-reply fade tuning and rounded clipping.
- Reaction-emoji pop animation.
- Source-photo to stock-gallery container transform, tap-time placeholder, page-aware return handling, and vertical drag-dismiss.
- Foreground-conversation typing indicator.
- Four activity animation XML resources and the conversation status-row layout.
- Live-tuning and automatic diagnostic delivery helpers.

## Project layout

Copy `UI Advances - Source Exact/src/main/` into the app module, preserving the package paths initially:

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

## Dependencies

- AndroidX Core/View
- Material Components for Android
- A `com.textrcs.diag.ScreenTracer` implementation connected to the app's logging layer
- Activity themes configured for translucent rounded transitions

Add the animation style to the activity theme:

```xml
<style name="TextRcsParallaxAnimation" parent="@android:style/Animation">
    <item name="android:activityOpenEnterAnimation">@anim/textrcs_overlay_enter</item>
    <item name="android:activityOpenExitAnimation">@anim/textrcs_overlay_partial_exit</item>
    <item name="android:activityCloseEnterAnimation">@anim/textrcs_overlay_partial_enter</item>
    <item name="android:activityCloseExitAnimation">@anim/textrcs_overlay_exit</item>
</style>

<style name="ConversationTheme" parent="Theme.Material3.DayNight.NoActionBar">
    <item name="android:windowAnimationStyle">@style/TextRcsParallaxAnimation</item>
    <item name="android:windowIsTranslucent">true</item>
    <item name="android:windowBackground">@android:color/transparent</item>
</style>
```

## Public API and exact invocation map

| User-visible advance | Source owner | Source-level invocation |
|---|---|---|
| Rounded activity open/close with dim and under-screen parallax | `ConvoCornerAnim` | Once: `registerActivityTracking(application)`. Destination `onCreate`: `attach(activity)`. Before back/up finishes: `attachClose(activity)`. Emergency cleanup: `reset(activity)`. |
| Conversation content follows the keyboard; attachment panel can temporarily reclaim layout | `ImeSyncAnim` | Conversation `onCreate`: `attach(activity)`. Attachment panel open/close: `setPlusPanelOpen(true/false)`. Map its TextRCS view lookup to the app's message-list-plus-compose container and top bar. |
| Quick-reply fade uses a tuneable duration and explicit cubic easing | `PopupAnimHooks` | When constructing the popup animator: `duration = fadeMs()` and `interpolator = interpolator()`. Call `ensurePolling()` when live tuning is enabled. |
| Reaction emoji visibly pops when inserted | `ReactionPop` | After the reaction view is attached and laid out: `popEmoji(reactionView)` or `popEmoji(reactionView, delayMs)`. |
| Quick-reply card gets rounded visible bounds | `QuickReplyCorners` | After the quick-reply root is laid out: `apply(rootView)`. |
| Photo expands from the tapped pixels into the stock gallery | `MorphGalleryLauncher` + `GalleryMorph` | Source tap: `launch(tappedImageView, conversationId, messageId)`. Gallery `onCreate`: `GalleryMorph.onCreate(this)`. If the gallery exposes a reliable image-ready callback, call `onImageReady(this, photoView)`. |
| Gallery closes through the return transition | `GalleryClose` | Back/up/dismiss: `close(activity)`. A shared back runner can call `smartClose(activity)` to preserve plain finish behavior on other screens. |
| Vertical drag dismiss without breaking zoom or paging | `DragDismissTouchListener` | Installed by `GalleryMorph` on the active photo page while preserving the photo view's original touch delegate. |
| Typing feedback is scoped to the visible conversation | `TypingIndicator` | Window focus: `onConvoFocus(activity, hasFocus)`. Transport event: `onTypingEvent(conversationId, senderNumber, started)`. Incoming bubble: `onMessageDelivered(senderNumber)`. |

## Minimal source call sites

Application startup:

```kotlin
override fun onCreate() {
    super.onCreate()
    ConvoCornerAnim.registerActivityTracking(this)
    LiveTune.start(this)
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

## App integration points

| Bundled TextRCS contract | App integration |
|---|---|
| `ConvoActivity` | Conversation/detail activity. |
| `messageListAndSendArea` numeric lookup | `R.id` or direct binding for the message list plus compose region. |
| `actionbarContainer` numeric lookup | Toolbar/header binding used for clipping. |
| Textra's custom resize-suppression fields | Remove when the app has a single inset owner; otherwise route competing resize paths through one interface. |
| Gallery class-name and `convoId`/`msgId` extras | Gallery activity and stable photo identity contract. |
| Reflection for PhotoView/ViewPager | Direct typed references when those classes are available in source. Preserve the current gesture arbitration rules. |
| Typing recipient reflection | Conversation model participant list. Keep normalized-phone matching or use a stable participant ID. |
| URLs in `LiveTune` and `Imelog` | App-specific configuration and diagnostic endpoints. |

## Motion and lifecycle invariants

- All motion uses explicit time-based animators and cubic easing.
- The rounded destination window uses a translucent theme and transparent background so clipped corners reveal the previous screen.
- The under-screen parallax and destination slide use synchronized durations.
- The IME path has one layout owner rather than combining framework resize, app resize, and translation.
- The photo transition name belongs to the image, not the whole bubble.
- A tap-time bitmap placeholder starts the photo transform before the full decode; the existing image loader later replaces that same destination view.
- Drag-dismiss is enabled only at minimum zoom and for a mostly vertical, single-pointer gesture; horizontal paging and zoom/pan retain ownership.
- Gallery close uses the return-transition path only for the gallery; other activities retain plain `finish()`.
- Long-lived activity references remain weak, and callbacks are cleared when the owning screen is destroyed.

## Bring-up sequence

1. Resolve the integration points above and build the app.
2. Open and close a conversation; inspect the rounded corners, dim, and under-screen motion.
3. Open and close the keyboard; watch the list and compose bar move together without a resize jump or top-bar overdraw.
4. Open and close the attachment panel from both keyboard-visible and keyboard-hidden states.
5. Open quick reply and add a reaction with accessibility motion settings enabled and disabled.
6. Tap an image bubble, swipe pages, pinch zoom, drag vertically to dismiss, and use system back.
7. Trigger typing start/stop and an arriving message in the visible conversation.
8. Read the automatically delivered diagnostics from the configured endpoint.
