# ctrlB product definition

ctrlB is a video-first conversational editor.

The user gives ctrlB footage and describes the result they want. A multimodal editing agent watches and listens to the footage, investigates uncertain moments with specialist tools, discusses consequential choices with the user, and proposes an edit. The device applies the approved edit deterministically, previews it, and exports it.

The core experience is:

1. Import footage.
2. Ask for an outcome in chat.
3. Watch the agent inspect the actual footage and report safe operational progress.
4. Answer questions only when a meaningful editorial choice is genuinely ambiguous.
5. Review the proposed cut in the video preview.
6. Approve, revise, reject, or undo through chat.
7. Export locally at the source orientation and at source quality or lower.

ctrlB is not a filter wizard and not a rules engine pretending to understand video. Deterministic detectors are useful tools, but the editor is a multimodal agent with direct access to the authorized media.

## Product principles

- The agent sees the video, not merely a lossy prose summary assembled by the client.
- Analysis is iterative. There is no fixed two-pass evidence limit.
- Work ends when the proposal is grounded, the agent needs the user, a policy budget is reached, the user cancels, or a tool fails terminally.
- Operational activity is visible; hidden model chain-of-thought is not.
- Approved edits remain typed, versioned, deterministic, previewable, exportable, and exactly undoable.
- The server owns the canonical wire contract and publishes generated client models.
- The client validates commands because local media mutation must never trust arbitrary model output.
- Source orientation is detected from media metadata and preserved. Chat cannot silently change framing.
- Output resolution never exceeds source resolution. Lower-quality preview/export is allowed.
- Users choose whether original media, a high-fidelity proxy, or only selected derivatives may enter cloud analysis.
- The initial direct-video agent uses Gemini 3.6 Flash only. Additional planning models remain out of scope until evaluation demonstrates a concrete need.

## Future enhancements

Person or object segmentation, transparent backgrounds, background replacement,
and temporally consistent removal or inpainting are promising future editing
capabilities. They are not active implementation commitments. Promote them only
after the core video agent, grounding, preview parity, and representative-device
evaluation work end to end.
