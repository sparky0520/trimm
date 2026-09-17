# Ticket 10: Video Duration Parsing (AssetPicker.tsx)

## Description
When importing a video from the image library using `react-native-image-picker`, the duration is currently stubbed. It assumes every imported clip starts at 0 and is exactly 5 seconds long (`start: 0, end: 5`).

## Requirements
- Read the actual duration of the media file when imported.
- Pass the correct duration to the `add_clip` tool call instead of the hardcoded 5 seconds.
- Consider handling duration extraction using `ffmpeg-kit-extended` (ffprobe) or native video APIs.
