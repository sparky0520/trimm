# iOS Video Engine Limitations

This document outlines known limitations and architectural compromises in the iOS (AVFoundation) implementation of the Universal Video JSON DSL.

## 1. Blend Modes (`blendMode`)
Currently, `blendMode` operations (e.g., `screen`, `multiply`, `overlay`) requested via the JSON DSL are **stubbed with a warning** and will fallback to a normal visual composite.

**Why:**
AVFoundation uses `AVMutableVideoCompositionLayerInstruction` to provide efficient, hardware-accelerated methods for layer manipulation, natively supporting properties like scaling, movement, and opacity ramps (`setTransformRamp`, `setOpacityRamp`). 

However, layer instructions **do not** support blend modes natively. To achieve standard blend modes in AVFoundation, the engine must discard built-in instructions and utilize a fully custom `AVVideoCompositing` class. This requires intercepting every single frame as a raw pixel buffer (using Metal or CoreImage), blending the pixels manually based on Z-index, and returning the output. Due to the high complexity and performance overhead, custom compositing has been deferred.

## 2. CoreImage vs Layer Instructions Conflict
When the JSON DSL specifies Mathematical Effects (such as `colorMatrix`), the engine applies these using the specialized CoreImage handler block: `AVMutableVideoComposition(asset:applyingCIFiltersWithHandler:)`.

**Limitation:**
According to Apple's documentation, applying custom CoreImage filters via this handler disables standard `instructions` and `layerInstructions`. Currently, the engine handles this gracefully by generating a `videoComposition`, but complex combinations (e.g., a spatial zoom running at the exact same time as a heavy CoreImage color matrix on multiple layers) may require shifting all transform logic into the `CIFilter` pipeline or eventually migrating to a custom `AVVideoCompositing` class. 

## 3. Math Fallbacks & Safeguards
Because the LLM generates the JSON DSL, parameters can occasionally be hallucinated. The iOS parser includes strict safeguards:
- **Zero Scale Prevention:** If the LLM requests a scale of `0.0`, the engine artificially clamps this to `0.01` to prevent `CGAffineTransform` divide-by-zero crashes.
- **Unknown Effects:** Hallucinated effects (e.g., `"type": "lens_flare"`) are safely ignored. The engine logs a warning rather than crashing the timeline.
