package ai.trimm.mobile.ui

sealed interface ChatElement {
    val timestamp: Long
    data class InitialRequest(override val timestamp: Long, val request: String) : ChatElement
    data class Findings(override val timestamp: Long, val read: ai.trimm.mobile.domain.EditorialRead) : ChatElement
    data class Turn(override val timestamp: Long, val turn: ai.trimm.mobile.domain.EditorialConversationTurn) : ChatElement
    data class Checkpoint(override val timestamp: Long, val checkpoint: ai.trimm.mobile.domain.AppliedEditCheckpoint) : ChatElement
}
