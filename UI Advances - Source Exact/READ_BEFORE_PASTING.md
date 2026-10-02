# Read before pasting

This directory is a byte-identical source snapshot of the active TextRCS UI owners, arranged as a normal Android `src/main` tree. It is deliberately not a claim that every file can be dropped into an unrelated app unchanged.

## Production-port blockers

### Lifecycle-bound continuous work

The current TextRCS audit identified two pieces of lifecycle debt that a receiving source project must correct while mapping the bundle:

- `ImeSyncAnim` owns recurring live-config work and keeps activity/window/view state. In a source project, bind screen-specific work to the conversation screen's lifecycle, cancel it when the lifecycle is destroyed, and stop active collection while the screen is stopped.
- `LiveTune` starts one daemon polling loop with no public stop path. Keep it only when live tuning is an explicit product requirement, and give it an application-foreground or explicit owner lifecycle instead of an unconditional process-lifetime loop.

Android's current guidance supports this boundary: long-lived references to `Activity`, `Context`, `View`, and `Runnable` objects can retain destroyed screens; `lifecycleScope` cancels at destruction; `repeatOnLifecycle(STARTED)` cancels and relaunches UI-related collection as the screen stops and starts.

Authoritative references:

- https://developer.android.com/studio/views/capture-heap-dump-views
- https://developer.android.com/topic/libraries/architecture/views/coroutines-views
- https://developer.android.com/topic/architecture/views/recommendations-views

### Host-specific contracts

- Replace numeric TextRCS/Textra resource lookups with the receiving app's typed view bindings.
- Replace gallery class names and message extras with the receiving app's stable photo identity contract.
- Replace typing-recipient reflection with the receiving app's actual participant model.
- Review and replace the live-tuning and diagnostic HTTPS endpoints before distribution.
- Preserve the original touch delegate when installing vertical photo drag-dismiss so zoom, pan, taps, and paging retain their owners.

## Verification boundary

The exported Kotlin and XML files were verified byte-for-byte against their current TextRCS source owners. No Android compilation, installation, rendered-frame review, or real UI interaction was authorized for this handoff, so those checks remain required in the receiving app.

