# Video-agent architecture

```text
Android preparation
  -> complete AssemblyAI transcript
  -> start or resume a conversation-backed LangGraph turn
      -> source <= 30s: upload complete video at LOW resolution
      -> source > 30s: upload the first 30s at LOW resolution
  -> Gemini terminal response OR focused proposal/evidence tool call
  -> Android uploads the exact requested <=30s MP4
  -> same run continues with direct video input
  -> later user messages resume the same conversation thread
  -> validated proposal -> preview -> apply checkpoint
```

## Agent boundary

The LangGraph agent lives behind `/v1/conversations` beside the backend's conventional routes. Gemini is its reasoning model; focused proposal tools and governed media tool calls are its actions. The existing private Postgres run store is an internal compatibility persistence adapter and is not the public product model.

Gemini receives the conversation, source metadata, complete source-relative timestamped transcript, current project revision, existing accepted edits, persisted video observations, and authorized video inputs. It never receives local paths, provider keys, executable commands, or rendered output.

`inspect_video_range(sourceId, startMs, endMs, resolution, uncertainty)` creates a conversation tool call for an additional device media artifact. `LOW` is the default. `HIGH` requires an overlapping completed `LOW` range and a nonempty explanation of what remained unclear. The cloud rejects ranges over 30 seconds and artifacts that do not belong to the same user, project, device session, source, or conversation.

Focused, side-effect-free proposal tools cover fumbles, awkward silences, cleanup, levels, sound effects, music, captions, color, and semantic visuals. Their descriptions carry operation-specific editorial policy. LangGraph returns validation results to Gemini until `finalize_edit_program` atomically constructs the canonical proposal.

The phone and cloud match continuations by opaque job ID. If the cloud requested job A, the continuation must contain exactly A: no omitted, additional, stale, or unrelated IDs. An identical attached request terminates instead of looping.

Gemini file references are reused in the active provider process while valid; authorized bytes remain governed by the six-hour analysis retention policy. Provider usage metadata is persisted per call and aggregated for the conversation. Price is estimated from recorded tokens and current configured model rates; it does not control execution.

## Grounding and execution

Gemini emits timestamped observations and a terminal edit program, clarification, or fallback. Observation IDs are unique within the conversation and must lie inside supplied video ranges. Deterministic validators reject invented evidence, stale project revisions, unsupported operations, unsafe semantic framing, and ungrounded contextual speech cuts.

Android remains responsible for deterministic preview and export. Local waveform and frame measurements may still support rendering and technical analysis, but they are not presented as semantic video understanding.

## Independent legacy planner

The Kimi-backed planner HTTP route is independent and remains unchanged. Old Gemini frame/audio/crop evidence contracts are not supported.
