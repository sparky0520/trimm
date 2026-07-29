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

`ExportCoordinator` converts the project into a Media3 `Composition`:

1. `RemoveFumbles` becomes retained source ranges.
2. Each retained range becomes a clipped `MediaItem`.
3. `EditedMediaItemSequence` joins clips in story order.
4. Audio processors apply cleanup, leveling, music, ducking, and sound effects.
5. video effects apply color, captions, semantic overlays, and supported reframing.
6. Media3 `Transformer` encodes H.264 or HEVC.

Preview uses the same coordinator with a smaller resolution and bitrate. Export uses the approved project settings.

Source rotation determines the displayed width, height, and exact output aspect. Chat and platform names do not change portrait, landscape, or square framing. Draft, preview, and final export use the same ceiling calculation: device limits may reduce the output, and mixed-source projects use the lowest per-axis source ceiling, but no output dimension exceeds the available displayed source pixels.

## Current limitation

Preview is still a rendered MP4, so it is slower than desktop’s interactive Remotion player. The target is a canonical Media3 playback composition that updates immediately while the final export continues to use `Transformer`.
