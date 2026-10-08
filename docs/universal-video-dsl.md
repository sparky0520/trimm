# Universal Video JSON DSL Architecture

## Overview
This document outlines the architecture for the "Universal Video JSON DSL" (Domain Specific Language). This architecture separates the AI Agent (the "Brain/Director") from the rendering client (the "Projectionist/Engine"). 

The AI outputs a strictly typed JSON document representing video edits. 

### Two-Tiered Engine
We use a two-tiered system for edits:
1. **Simple Tool Definitions:** For 90% of requests (basic text overlays, fast-forwards, jump cuts, audio ducking, simple fades), the LLM outputs a simplified tool format (e.g., `propose_add_text_overlay`). The iOS client translates these native intents instantly.
2. **The Video DSL (God Tool):** For complex requests (VHS glitches, multi-layer compositing, color matrices), the LLM generates a complete Universal JSON DSL document using universal mathematical primitives (time, space, color, composition). Any client (iOS AVFoundation, Android Media3) acts merely as a parser that translates this JSON into native rendering commands.

---

## 1. The Core JSON Schema

The schema represents a "Scene Graph" or Timeline. All time measurements are strictly in **milliseconds** (`timeMs`, `durationMs`) for exact precision across platforms.

```json
{
  "project": {
    "dimensions": { "width": 1080, "height": 1920 },
    "fps": 30,
    "durationMs": 10000 
  },
  "timeline": {
    "layers": [
      {
        "id": "background_video",
        "type": "media",
        "source": { "url": "local://clip1.mp4", "mediaStartTimeMs": 0 },
        "startMs": 0,
        "durationMs": 10000,
        
        "spatial": {
          "anchor": { "x": 0.5, "y": 0.5 },
          "keyframes": [
            { "timeMs": 0, "x": 540, "y": 960, "scale": 1.0, "rotation": 0, "easing": "linear" },
            { "timeMs": 10000, "x": 540, "y": 960, "scale": 1.2, "rotation": 0, "easing": "ease-out" }
          ]
        },
        
        "compositing": {
          "blendMode": "normal",
          "opacity": {
            "keyframes": [
              { "timeMs": 0, "value": 1.0, "easing": "linear" }
            ]
          }
        },
        
        "audio": {
          "volume": {
            "keyframes": [
              { "timeMs": 0, "value": 1.0, "easing": "linear" },
              { "timeMs": 8000, "value": 1.0, "easing": "linear" },
              { "timeMs": 10000, "value": 0.0, "easing": "linear" }
            ]
          }
        },
        
        "effects": [
          {
             "type": "colorMatrix",
             "matrix": [
                1.0, 0, 0, 0, 0,
                0, 1.0, 0, 0, 0,
                0, 0, 1.0, 0, 0,
                0, 0, 0, 1, 0
             ]
          }
        ]
      }
    ]
  }
}
```

---

## 2. Universal Building Blocks

### Spatial Keyframes
Controls position (`x`, `y`), `scale`, and `rotation`.
- `timeMs`: Time relative to the layer's `startMs`.
- `easing`: Allows interpolation (`linear`, `ease-in`, `ease-out`, `ease-in-out`).

### Compositing
- `blendMode`: Enums like `normal`, `multiply`, `screen`, `overlay`.
- `opacity`: Array of keyframes (value between `0.0` and `1.0`).

### Mathematical Effects
- **ColorMatrix:** A 4x5 array (standard SVG/CoreImage color matrix format) to manipulate RGBA channels. This is the primary driver for "Vibes" and color grading.

---

## 3. System Prompting for AI Creativity (The "Recipes")

Because we use primitives, the LLM needs guidance on how to combine them to achieve artistic results. The System Prompt for the LLM must include "Recipes".

**Example System Prompt Injection:**
> You are an expert video editor. You express edits strictly via the JSON DSL. To achieve specific vibes, combine primitives:
> 
> *Recipe: Vintage Film / Warmth*
> Use a `colorMatrix` to boost reds and yellows. Reduce contrast slightly.
> `[1.2, 0.1, 0, 0, 0,   0, 0.9, 0, 0, 0,   0, 0, 0.8, 0, 0,   0, 0, 0, 1, 0]`
> 
> *Recipe: VHS Glitch*
> Duplicate the target media layer 3 times perfectly overlapping. On layer 1, apply a colorMatrix isolating Red. On layer 2, isolate Green. On layer 3, isolate Blue. Set all three layers `blendMode` to `screen`. Apply rapid spatial keyframes (jittering `x` by +/- 5 pixels) to the Red and Blue layers.

## 4. Client Implementation Strategy
Clients must implement parsers *incrementally*. Do not attempt to build the entire engine at once.
- Parse `project` and `timeline.layers`. Render a static video.
- Parse `spatial`. Add movement.
- Parse `compositing`. Add fades.
- Parse `effects`. Add color grading.
