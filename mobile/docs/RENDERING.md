# Android rendering

Android does not use Remotion. It uses AndroidX Media3.

## Project configuration

`MobileProject` is the saved edit recipe. It contains:

- ordered source URIs and source metadata;
- transcript and analysis references;
- typed edit operations;
- export width, height, codec, and bitrate;
- revision and undo snapshots.

It does not contain rendered video.

## Preview and export

`ExportCoordinator` converts the project into one canonical Media3 `Composition`:

1. `RemoveRanges` becomes retained source ranges.
2. Each retained range becomes a clipped `MediaItem`.
3. `EditedMediaItemSequence` joins clips in story order.
4. Audio processors apply cleanup, leveling, music, ducking, and sound effects.
5. video effects apply color, captions, semantic overlays, and supported reframing.
6. Media3 `Transformer` encodes H.264 or HEVC.

The interactive `CompositionPlayer` and final `Transformer` export consume that same composition factory. Applying, toggling, reordering, or undoing an edit changes the persisted project revision and recreates the interactive player immediately; export later loads that same persisted revision. A lower-resolution rendered preview remains available for proposal simulation and fallback, but is not the editor's source of truth.

Source rotation determines the displayed width, height, and exact output aspect. Chat and platform names do not change portrait, landscape, or square framing. Draft, preview, and final export use the same ceiling calculation: device limits may reduce the output, and mixed-source projects use the lowest per-axis source ceiling, but no output dimension exceeds the available displayed source pixels.

## Current limitation

`CompositionPlayer` is still an experimental Media3 API. The canonical composition factory isolates that dependency so player API changes do not create a second edit interpretation.
