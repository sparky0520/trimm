# Implementation Tickets: AI-Driven Video Engine

This document breaks down the implementation of the Universal Video JSON DSL into actionable tickets (Vertical Slices) for an AI agent to execute. 

**CRITICAL DIRECTIVE FOR IMPLEMENTING AGENT:** 
Do not attempt to build the entire parser/engine in one PR. Build incrementally. Each ticket must result in a compilable, testable state. Focus heavily on robustness (handling nulls, missing keys, unknown effects) so the parser does not crash on malformed LLM outputs.

---

## Ticket 1: Core Schema & Static Parsing (The Foundation)
**Goal:** Define the JSON Schema in code and parse a single static video track.

**Tasks:**
1. Create TypeScript types / Zod schema representing the core JSON DSL (Project, Timeline, Layer structure).
2. Create the "Dummy LLM Output" JSON file locally for testing.
3. Build the initial Native Parser (iOS/Android/RN).
    - Read the `project` dimensions and duration.
    - Loop through `timeline.layers`.
    - If `layer.type == "media"`, load the `source.url`.
    - Insert the media into the native composition engine (e.g., AVFoundation `AVMutableComposition`) based on `startMs` and `durationMs`.
4. **Validation:** The player should successfully preview a single, unedited video clip based purely on the JSON file.

---

## Ticket 2: Spatial Keyframes (Movement)
**Goal:** Introduce the `spatial` block to allow scaling, moving, and rotating.

**Tasks:**
1. Update the Parser to look for the `layer.spatial.keyframes` array.
2. Implement Keyframe interpolation logic.
    - Native side must map JSON `timeMs` to native time structs (e.g., `CMTime`).
    - Parse `x`, `y`, `scale`, and `rotation` values.
    - Handle `easing` types (`linear` as default).
3. Connect parsed keyframes to the native rendering engine (e.g., `AVMutableVideoCompositionLayerInstruction.setTransformRamp` in iOS).
4. **Validation:** Update the dummy JSON to include a zoom (`scale` 1.0 -> 1.5). The video should slowly zoom in over time.

---

## Ticket 3: Compositing & Opacity
**Goal:** Allow multiple layers to stack and blend, and introduce opacity fades.

**Tasks:**
1. Update Parser to handle multiple layers in the `layers` array (respecting array order as Z-index).
2. Parse the `compositing.opacity` keyframes array.
    - Connect to native opacity ramps.
3. Parse the `compositing.blendMode` string.
    - Map universal strings (`"screen"`, `"multiply"`) to native blend mode equivalents (e.g., Core Graphics blend modes).
    - Implement fallback (if blendMode is unknown, default to `"normal"`).
4. **Validation:** Update dummy JSON with two overlapping clips. The top clip should fade from opacity 1.0 to 0.0, revealing the clip underneath.

---

## Ticket 4: Audio Mixing & Fades
**Goal:** Introduce audio control so the AI can mute tracks, adjust volume, or fade audio out.

**Tasks:**
1. Update Parser to look for the `layer.audio.volume.keyframes` array.
2. Hook into the native audio mixer engine.
    - iOS: Build an `AVMutableAudioMix` and create `AVMutableAudioMixInputParameters` for the specific audio track. Use `setVolumeRamp`.
    - Android: Map to Media3 audio processors.
3. **Validation:** Update dummy JSON to include an audio fade out over the last 2 seconds. The exported video should demonstrate the fade.

---

## Ticket 5: Mathematical Effects (ColorMatrix)
**Goal:** Implement the primary tool for AI "Vibes" and color grading.

**Tasks:**
1. Update Parser to loop through the `layer.effects` array.
2. Implement a `switch` or factory pattern to handle `effect.type`.
    - If `type == "colorMatrix"`, extract the 4x5 float array.
3. Hook the matrix into the native engine.
    - iOS: Apply `CIColorMatrix` via `AVVideoComposition(asset:applyingCIFiltersWithHandler:)`.
    - Android: Apply `ColorMatrixColorFilter` or custom GL Shader.
4. **Resilience check:** Add an unknown effect `"type": "fake_lens_flare"` to the test JSON. The parser must log a warning and continue rendering the ColorMatrix, **without crashing**.
5. **Validation:** Apply a heavy red tint via ColorMatrix in the JSON. The preview should reflect the tint.

---

## Ticket 5: LLM Integration & Structured Output
**Goal:** Connect the actual AI to generate the DSL dynamically based on user prompts.

**Tasks:**
1. Write the System Prompt (The "Director's Handbook") incorporating the DSL Schema and "Recipes" (as outlined in `universal-video-dsl.md`).
2. Implement OpenAI/Gemini Structured Output (JSON Schema mode) to guarantee the AI returns JSON matching the DSL structure.
3. Hook the LLM output directly into the Parser pipeline established in Tickets 1-4.
4. **Validation:** Prompt the UI: "Make it feel vintage and warm." The LLM should generate a JSON containing a warm `colorMatrix`, which immediately renders on screen.
