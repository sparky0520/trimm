# Ticket 12: Frame-by-Frame Scrubbing (Timeline.tsx / App.tsx)

## Description
While the timeline UI successfully scrubs back and forth and updates the `currentTime` shared value, it's not yet tied to a real video decoder/player engine that seeks video frames natively.

## Requirements
- Connect the timeline's `currentTime` to the actual video player engine.
- Ensure smooth frame-by-frame visual scrubbing when the user drags the playhead.
- Handle pausing playback effectively when `onScrubStart` is triggered.
