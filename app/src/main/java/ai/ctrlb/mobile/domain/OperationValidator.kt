package ai.ctrlb.mobile.domain

object OperationValidator {
    fun isRendered(operation: EditOperation): Boolean = when (operation) {
        is AddTransition, is AddMotionPreset -> false
        else -> true
    }

    fun isPlannerAvailable(operation: EditOperation): Boolean =
        isRendered(operation) && operation !is SetColorGrade

    fun visible(operations: List<EditOperation>): List<EditOperation> =
        operations.filter(::isRendered)

    fun renderFingerprint(operations: List<EditOperation>): List<String> =
        operations.filter { it.enabled && isRendered(it) }.map { operation ->
            when (operation) {
                is RemoveFumbles -> "timeline:${operation.ranges}"
                is SetColorGrade -> "video-color:${operation.grade}"
                is SetAudioCleanup -> "dialogue-cleanup:${operation.strength}"
                is SetAudioLevels -> "dialogue-levels:${operation.dialogueLufs}:${operation.musicDuckDb}"
                is AddSoundEffect -> "audio-effect:${operation.atSourceMs}:${operation.catalogId}:${operation.gainDb}"
                is SetBackgroundMusic -> "audio-music:${operation.catalogId}:${operation.gainDb}:${operation.loop}:${operation.structure?.version}"
                is AddCaptions -> "video-captions:${operation.cues}:${operation.style}"
                is ApplySemanticVisual -> "semantic-visual:${operation.intent}:${operation.treatment}:${operation.range}:${operation.subject}:${operation.motion}:${operation.framing}"
                is AddTransition, is AddMotionPreset -> error("Unrendered operation reached render contract")
            }
        }

    fun basis(operation: EditOperation): String = when (operation) {
        is RemoveFumbles -> "Transcript evidence • ${operation.reasons.joinToString()}"
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
            is RemoveFumbles -> { require(op.confidence in 0f..1f); require(op.ranges.isNotEmpty()); op.ranges.forEach { require(it.endMs <= project.durationMs) } }
            is SetColorGrade -> Unit
            is SetAudioCleanup -> require(op.strength in 0f..1f)
            is SetAudioLevels -> { require(op.dialogueLufs in -24f..-12f); require(op.musicDuckDb in -18f..-3f) }
            is AddTransition -> { require(op.atSourceMs in 0..project.durationMs); require(op.durationMs in 120..1_000) }
            is AddMotionPreset -> { require(op.range.endMs <= project.durationMs); require(op.intensity in 0f..1f) }
            is AddSoundEffect -> { require(op.atSourceMs in 0..project.durationMs); require(op.gainDb in -36f..0f) }
            is SetBackgroundMusic -> {
                require(op.gainDb in -40f..-6f)
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
}
