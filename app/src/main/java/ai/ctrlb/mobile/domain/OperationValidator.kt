package ai.ctrlb.mobile.domain

object OperationValidator {
    fun isRendered(operation: EditOperation): Boolean = when (operation) {
        is AddTransition, is AddMotionPreset -> false
        else -> true
    }

    fun isPlannerAvailable(operation: EditOperation): Boolean = isRendered(operation)

    fun visible(operations: List<EditOperation>): List<EditOperation> =
        operations.filter(::isRendered)

    fun renderFingerprint(operations: List<EditOperation>): List<String> =
        operations.filter { it.enabled && isRendered(it) }.map { operation ->
            when (operation) {
                is RemoveRanges -> "timeline-range:${operation.ranges}"
                is RemoveFumbles -> "timeline:${operation.ranges}"
                is RemoveAwkwardSilences -> "timeline-silence:${operation.ranges}:${operation.pacing}"
                is SetColorGrade -> "video-color:${operation.grade}:${operation.target}:${operation.parameter}:${operation.value}"
                is SetAudioCleanup -> "dialogue-cleanup:${operation.strength}:${operation.target}"
                is SetAudioLevels -> "dialogue-levels:${operation.dialogueLufs}:${operation.musicDuckDb}"
                is AddSoundEffect -> "audio-effect:${operation.atSourceMs}:${operation.catalogId}:${operation.gainDb}"
                is SetBackgroundMusic -> "audio-music:${operation.catalogId}:${operation.gainDb}:${operation.loop}:${operation.structure?.version}"
                is AddCaptions -> "video-captions:${operation.cues}:${operation.style}"
                is ApplySemanticVisual -> "semantic-visual:${operation.intent}:${operation.treatment}:${operation.range}:${operation.subject}:${operation.motion}:${operation.framing}"
                is AddTransition, is AddMotionPreset -> error("Unrendered operation reached render contract")
            }
        }

    fun basis(operation: EditOperation): String = when (operation) {
        is RemoveRanges -> "Transcript evidence • ${operation.reason.ifBlank { "Range removal" }}"
        is RemoveFumbles -> "Transcript evidence • ${operation.reasons.joinToString()}"
        is RemoveAwkwardSilences -> "Audiovisual pause evidence • ${operation.pacing} pacing"
        is SetColorGrade -> "Frame evidence"
        is SetAudioCleanup -> "Default • speech-safe local processing"
        is SetAudioLevels -> "Default • ${operation.dialogueLufs.toInt()} LUFS dialogue target"
        is AddSoundEffect -> "User preference • ${operation.catalogId}"
        is SetBackgroundMusic -> "User preference • ${operation.catalogId}"
        is AddCaptions -> "Transcript evidence • ${operation.cues.size} timed cues"
        is ApplySemanticVisual -> "Visual evidence • ${operation.subject.kind.name.lowercase()} ${operation.subject.evidenceId}"
        is AddTransition, is AddMotionPreset -> "Unavailable • not rendered"
    }

    fun validate(project: MobileProject, proposed: List<EditOperation>, expectedRevision: Long = project.revision): List<EditOperation> {
        require(expectedRevision == project.revision) { "Stale edit proposal" }
        require(proposed.map { it.id }.distinct().size == proposed.size) { "Duplicate operation id" }
        require(proposed.all(::isPlannerAvailable)) { "Operation is unavailable without render support or frame evidence" }
        proposed.forEach { op -> when (op) {
            is RemoveRanges -> { require(op.confidence in 0f..1f); require(op.ranges.isNotEmpty()); op.ranges.forEach { require(it.endMs <= project.durationMs) } }
            is RemoveFumbles -> { require(op.confidence in 0f..1f); require(op.ranges.isNotEmpty()); op.ranges.forEach { require(it.endMs <= project.durationMs) } }
            is RemoveAwkwardSilences -> { require(op.confidence in 0f..1f); require(op.pacing in setOf("natural", "tight")); require(op.ranges.isNotEmpty()); op.ranges.forEach { require(it.endMs <= project.durationMs) } }
            is SetColorGrade -> {
                require(op.grade != null || (op.parameter in setOf("exposure", "temperature", "contrast", "saturation", "highlights", "shadows", "skinProtection") && op.value != null))
                validateTarget(project, op.target)
            }
            is SetAudioCleanup -> { require(op.strength in 0f..1f); validateTarget(project, op.target) }
            is SetAudioLevels -> {
                if (op.parameter == "dialogue_loudness") require(op.dialogueLufs in -24f..-12f)
                else if (op.parameter == "music_ducking") require(op.musicDuckDb in -18f..-3f)
                else { require(op.dialogueLufs in -24f..-12f); require(op.musicDuckDb in -18f..-3f) }
                require(op.attackMs in 0..2_000 && op.releaseMs in 0..5_000); validateTarget(project, op.target)
            }
            is AddTransition -> { require(op.atSourceMs in 0..project.durationMs); require(op.durationMs in 120..1_000) }
            is AddMotionPreset -> { require(op.range.endMs <= project.durationMs); require(op.intensity in 0f..1f) }
            is AddSoundEffect -> { require(op.atSourceMs in 0..project.durationMs); require(op.gainDb in -36f..0f); validateTarget(project, op.target) }
            is SetBackgroundMusic -> {
                require(op.gainDb in -40f..-6f)
                validateTarget(project, op.target)
                op.structure?.let { structure ->
                    require(structure.segments.zipWithNext().all { (a, b) -> a.timelineEndMs <= b.timelineStartMs })
                    require(structure.segments.all {
                        it.timelineStartMs >= 0 && it.fadeInMs >= 0 && it.fadeOutMs >= 0 &&
                            it.fadeInMs + it.fadeOutMs <= it.sourceRange.durationMs
                    })
                    require(structure.decisions.any { it.kind == AudioStructuralDecisionKind.SPEECH_TIMING })
                }
            }
            is AddCaptions -> { require(op.cues.isNotEmpty()); op.cues.forEach { require(it.range.endMs <= project.durationMs); require(it.text.isNotBlank() && it.text.length <= 120) } }
            is ApplySemanticVisual -> SemanticVisualPolicy.validate(op, project.durationMs)
        } }
        SemanticVisualPolicy.validateDensity(proposed.filterIsInstance<ApplySemanticVisual>().filter { it.enabled })
        return proposed
    }

    private fun validateTarget(project: MobileProject, target: OperationTarget?) {
        if (target == null) return // Backward-compatible projects used whole-project effects.
        val source = project.orderedSources.firstOrNull { it.fingerprint == target.sourceId }
        require(source != null && target.endMs <= source.durationMs) { "Operation target is outside its source" }
    }
}
