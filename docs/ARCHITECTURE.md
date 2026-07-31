# Video-agent architecture

```text
Android preparation
  -> complete AssemblyAI transcript
  -> start Gemini agent run
      -> source <= 30s: upload complete video at LOW resolution
      -> source > 30s: begin from transcript
  -> Gemini terminal response OR inspect_video_range tool call
  -> Android uploads the exact requested <=30s MP4
  -> same run continues with direct video input
  -> validated proposal -> preview -> apply checkpoint
```

## Agent boundary

Gemini receives the user request, source metadata, complete source-relative timestamped transcript, current project revision, existing accepted edits, persisted video observations, and authorized video inputs. It never receives local paths, provider keys, executable commands, or rendered output.

`inspect_video_range(sourceId, startMs, endMs, resolution, uncertainty)` is the only external tool. `LOW` is the default. `HIGH` requires an overlapping completed `LOW` range and a nonempty explanation of what remained unclear. The cloud rejects ranges over 30 seconds and jobs that do not belong to the same user, project, device session, source, or conversation.

The phone and cloud match continuations by opaque job ID. If the cloud requested job A, the continuation must contain exactly A: no omitted, additional, stale, or unrelated IDs. An identical attached request terminates instead of looping.

Gemini file references are reused in the active provider process while valid; authorized bytes remain governed by the six-hour analysis retention policy. Provider usage metadata is persisted per call and aggregated for the conversation. Price is estimated from recorded tokens and current configured model rates; it does not control execution.

## Grounding and execution

Gemini emits timestamped observations and a terminal edit program, clarification, or fallback. Observation IDs are unique within the conversation and must lie inside supplied video ranges. Deterministic validators reject invented evidence, stale project revisions, unsupported operations, unsafe semantic framing, and ungrounded contextual speech cuts.

Android remains responsible for deterministic preview and export. Local waveform and frame measurements may still support rendering and technical analysis, but they are not presented as semantic video understanding.

## Independent legacy planner

The Kimi-backed planner HTTP route is independent and remains unchanged. Old Gemini frame/audio/crop evidence contracts are not supported.
