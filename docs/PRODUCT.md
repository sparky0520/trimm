# ctrlB video agent

ctrlB is a conversational video editor. Android transcribes every source in full before planning. Gemini receives the timestamped transcript, project metadata, and existing accepted edits.

For a single source of 30 seconds or less, the run first authorizes and uploads the complete video, then Gemini processes it at low media resolution. For longer sources, Gemini starts from the complete transcript and calls `inspect_video_range` for the audiovisual context it needs. Each requested window is at most 30 seconds. Low resolution is normal; high resolution is allowed only for an overlapping range already inspected at low resolution and with a concrete remaining uncertainty.

The route-local LangGraph agent uses Gemini for reasoning inside durable chat conversations. It requests governed device media through `inspect_video_range`, builds operations through focused deterministic tools, asks rich clarification questions when needed, and finalizes one atomic proposal for preview and approval. The cloud validates ownership, consent, ranges, bytes, checksums, artifact provenance, operation parameters, and project revision without AI.

Gemini returns timestamped observations with stable conversation-scoped evidence IDs. Edit operations may cite only observations grounded in video supplied during that conversation. Complete videos and requested windows are sent directly to the planning call; there is no separate frame, crop, audio, or evidence-summary model call.

There is currently no model-dollar or requested-window count cutoff. Duplicate derivative requests stop safely. Every Gemini call records provider token metadata and an estimated USD price, and Android shows cumulative conversation usage with a per-call breakdown.

AssemblyAI remains the transcription provider. The separate Kimi planner route remains available and is not part of this Gemini agent loop.
