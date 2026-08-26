package ai.ctrlb.mobile.domain

object OperationValidator {
    fun isRendered(operation: EditOperation): Boolean = when (operation) {
        is AddMotionPreset -> false
        else -> true
    }

    fun isPlannerAvailable(operation: EditOperation): Boolean = isRendered(operation)

    fun visible(operations: List<EditOperation>): List<EditOperation> =
        operations.filter(::isRendered)

    fun renderFingerprint(operations: List<EditOperation>): List<String> =
        operations.filter { it.enabled && isRendered(it) }.map { operation ->
            when (operation) {
                is RemoveRanges -> "timeline-range:${operation.ranges}"
                is SetColorGrade -> "video-color:${operation.grade}:${operation.target}:${operation.parameter}:${operation.value}"
                is SetAudioLevels -> "dialogue-levels:${operation.dialogueLufs}:${operation.musicDuckDb}"

                is AddCaptions -> "video-captions:${operation.cues}:${operation.style}"
                is SetAspectRatio -> "video-aspect-ratio:${operation.ratio.name}"
                is AddTextOverlay -> "video-text-overlay:${operation.id}:${operation.text}"
                is AddBgm -> "audio-bgm:${operation.url}"
                is AddSfx -> "audio-sfx:${operation.target?.startMs}:${operation.target?.endMs}:${operation.url}"
                is AddSectionTransition -> "video-transition:${operation.id}:${operation.preset}"
                is AddMotionPreset -> error("Unrendered operation reached render contract")
            }
        }

    fun basis(operation: EditOperation): String = when (operation) {
        is RemoveRanges -> "Transcript evidence • ${operation.reason.ifBlank { "Range removal" }}"
        is SetColorGrade -> "Frame evidence"
        is SetAudioLevels -> "Default • ${operation.dialogueLufs.toInt()} LUFS dialogue target"

        is AddCaptions -> "Transcript evidence • ${operation.cues.size} timed cues"
        is SetAspectRatio -> "Aspect ratio: ${operation.ratio.name}"
        is AddTextOverlay -> "Custom Text Overlay Graphics"
        is AddBgm -> "Generated • ${operation.prompt}"
        is AddSfx -> "Generated • ${operation.prompt}"
        is AddSectionTransition -> "Transition • ${operation.preset}"
        is AddMotionPreset -> "Unavailable • not rendered"
    }

    fun validate(project: MobileProject, proposed: List<EditOperation>, expectedRevision: Long = project.revision): List<EditOperation> {
        require(expectedRevision == project.revision) { "Stale edit proposal" }
        require(proposed.map { it.id }.distinct().size == proposed.size) { "Duplicate operation id" }
        require(proposed.all(::isPlannerAvailable)) { "Operation is unavailable without render support or frame evidence" }
        val sourceOffsets = buildMap {
            var offset = 0L
            project.orderedSources.forEachIndexed { index, source -> 
                put("${source.fingerprint}-$index", offset)
                if (!containsKey(source.fingerprint)) put(source.fingerprint, offset)
                offset += source.durationMs 
            }
        }
        val resolved = proposed.map { op ->
            if (op is AddCaptions && op.cues.isEmpty()) {
                val words = project.analysis.words.filter { word ->
                    val matchesSpeaker = op.speakerId == null || word.speakerId == op.speakerId
                    val matchesTarget = op.target == null || (
                        sourceOffsets[op.target.sourceId]?.let { offset ->
                            word.range.startMs >= offset + op.target.startMs && word.range.endMs <= offset + op.target.endMs
                        } ?: false
                    )
                    matchesSpeaker && matchesTarget
                }
                op.copy(cues = CaptionSegmenter.segment(words))
            } else if (op is AddMotionPreset && op.range == null) {
                op.copy(range = TimeRange(0, project.durationMs))
            } else op
        }
        resolved.forEach { op -> when (op) {
            is RemoveRanges -> { require(op.confidence in 0f..1f); require(op.ranges.isNotEmpty()); op.ranges.forEach { require(it.endMs <= project.durationMs) } }
            is SetColorGrade -> {
                require(op.grade != null || (op.parameter in setOf("exposure", "temperature", "contrast", "saturation", "highlights", "shadows", "skinProtection") && op.value != null))
                validateTarget(project, op.target)
            }
            is SetAudioLevels -> {
                if (op.parameter == "dialogue_loudness") require(op.dialogueLufs in -24f..-12f)
                else if (op.parameter == "music_ducking") require(op.musicDuckDb in -18f..-3f)
                else { require(op.dialogueLufs in -24f..-12f); require(op.musicDuckDb in -18f..-3f) }
                require(op.attackMs in 0..2_000 && op.releaseMs in 0..5_000); validateTarget(project, op.target)
            }
            is SetAspectRatio -> { }
            is AddMotionPreset -> { require(op.range != null && op.range.endMs <= project.durationMs); require(op.intensity in 0f..1f) }

            is AddCaptions -> { require(op.cues.isNotEmpty()); op.cues.forEach { require(it.range.endMs <= project.durationMs); require(it.text.isNotBlank() && it.text.length <= 120) } }
            is AddTextOverlay -> { require(op.text.isNotBlank()); validateTarget(project, op.target) }
            is AddBgm -> { require(op.url.isNotBlank()) }
            is AddSfx -> { require(op.url.isNotBlank()); validateTarget(project, op.target) }
            is AddSectionTransition -> { require(op.preset.isNotBlank()); validateTarget(project, op.target) }
        } }
        return resolved
    }

    private fun validateTarget(project: MobileProject, target: OperationTarget?) {
        if (target == null) return // Backward-compatible projects used whole-project effects.
        val fingerprint = target.sourceId.substringBeforeLast("-")
        val source = project.orderedSources.firstOrNull { it.fingerprint == fingerprint }
        require(source != null && target.endMs <= source.durationMs) { "Operation target is outside its source" }
    }
}
