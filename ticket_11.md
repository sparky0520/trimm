# Ticket 11: Video Playback Rendering (PreviewEngine.tsx)

## Description
Actual video frames are not being rendered in the Skia canvas. The renderer currently uses a visual stub (`mockVideoContainer` and `mockVideoFrame`) which just draws a placeholder rectangle on the screen while a video node is active on the timeline.

## Requirements
- Implement actual video rendering inside or alongside the Skia canvas.
- Ensure videos play accurately according to their `start` and `end` times on the timeline.
- Coordinate with Skia layers so that overlays (like text) render correctly on top of the video frames.
