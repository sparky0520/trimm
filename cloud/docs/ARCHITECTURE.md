# Video-agent architecture

```text
Android preparation
  -> complete AssemblyAI transcript
  -> ask the backend planner (/v1/agent/plan) for a task plan
  -> for each task in the plan:
      -> execute the task statelessly against /v1/agent/execute
      -> Gemini returns a tool request, edit operations, or clarification
      -> Android fulfills evidence if requested, appending to the existing prompt state
      -> accumulate the results locally
  -> validated proposal -> preview -> apply checkpoint
```

## Agent boundary

The agent lives behind `/v1/agent/plan` and `/v1/agent/execute` beside the backend's conventional routes. Gemini is its reasoning model; focused proposal tools and governed media tool calls are its actions. Orchestration and state management are natively handled client-side by Android, so there is no Postgres state backend for the agent.

Gemini receives the conversation, source metadata, complete source-relative timestamped transcript, current project revision, existing accepted edits, persisted video observations, and authorized video inputs via a fully self-contained stateless payload. It never receives local paths, provider keys, executable commands, or rendered output.

`inspect_video_range(sourceId, startMs, endMs, resolution, uncertainty)` creates a conversation tool call for an additional device media artifact. `LOW` is the default. `HIGH` requires an overlapping completed `LOW` range and a nonempty explanation of what remained unclear. The cloud rejects ranges over 30 seconds and artifacts that do not belong to the same user, project, device session, or source.

Focused, side-effect-free proposal tools cover fumbles, awkward silences, cleanup, levels, sound effects, music, captions, color, and semantic visuals. Their descriptions carry operation-specific editorial policy.

Music and sound-effect placement is search-bound. Gemini must first call `search_background_music` or `search_sound_effects`. Default federation includes the governed registry, Freesound discovery when `FREESOUND_API_KEY` is configured, and a YouTube Audio Library assisted-search action. Registry results are render-licensed and checksum-backed. Fresh Freesound discoveries are marked authorization-required, and YouTube results remain assisted because its library search/download lives in YouTube Studio. A placement proposal may reference only a matching result returned with `renderable=true` in that conversation. The registry is operated automatically from provider acquisition, license audits, revocations, and deduplication; it is not a user-maintained collection. Mobile resolves the accepted ID from the same registry, downloads the acquired asset over HTTPS, verifies its SHA-256 checksum, and renders the local file.

Gemini file references are reused in the active provider process while valid; authorized bytes remain governed by the six-hour analysis retention policy. Provider usage metadata is returned to the client and aggregated per conversation. Price is estimated from recorded tokens and current configured model rates; it does not control execution.

## Grounding and execution

Gemini emits timestamped observations and a terminal edit program, clarification, or fallback. Observation IDs are unique within the conversation and must lie inside supplied video ranges. Deterministic validators reject invented evidence, stale project revisions, unsupported operations, unsafe semantic framing, and ungrounded contextual speech cuts.

Android remains responsible for deterministic preview and export. Local waveform and frame measurements may still support rendering and technical analysis, but they are not presented as semantic video understanding.

## Independent legacy planner

The Kimi-backed planner HTTP route is independent and remains unchanged. Old Gemini frame/audio/crop evidence contracts are not supported.
