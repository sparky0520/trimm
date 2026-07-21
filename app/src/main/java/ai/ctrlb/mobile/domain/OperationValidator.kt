package ai.ctrlb.mobile.domain

object OperationValidator {
    fun validate(project: MobileProject, proposed: List<EditOperation>, expectedRevision: Long = project.revision): List<EditOperation> {
        require(expectedRevision == project.revision) { "Stale edit proposal" }
        require(proposed.map { it.id }.distinct().size == proposed.size) { "Duplicate operation id" }
        proposed.forEach { op -> when (op) {
            is RemoveFumbles -> { require(op.confidence in 0f..1f); require(op.ranges.isNotEmpty()); op.ranges.forEach { require(it.endMs <= project.durationMs) } }
            is SetColorGrade -> Unit
            is SetAudioCleanup -> require(op.strength in 0f..1f)
            is SetAudioLevels -> { require(op.dialogueLufs in -24f..-12f); require(op.musicDuckDb in -18f..-3f) }
            is AddTransition -> { require(op.atSourceMs in 0..project.durationMs); require(op.durationMs in 120..1_000) }
            is AddMotionPreset -> { require(op.range.endMs <= project.durationMs); require(op.intensity in 0f..1f) }
            is AddSoundEffect -> { require(op.atSourceMs in 0..project.durationMs); require(op.gainDb in -36f..0f) }
            is SetBackgroundMusic -> require(op.gainDb in -40f..-6f)
            is AddCaptions -> { require(op.cues.isNotEmpty()); op.cues.forEach { require(it.range.endMs <= project.durationMs); require(it.text.isNotBlank() && it.text.length <= 120) } }
        } }
        return proposed
    }
}
