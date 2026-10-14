package ai.trimm.mobile

import ai.trimm.mobile.domain.*
import ai.trimm.mobile.planner.ClarificationQuestion
import ai.trimm.mobile.network.ChatSummary

data class ChatState(
    val sources: List<SourceMedia> = emptyList(),
    val preferences: WizardPreferences = WizardPreferences(),
    val creatorPreferences: CreatorPreferenceProfile = CreatorPreferenceProfile(),
    val project: MobileProject? = null,
    val compositionSchema: CompositionSchema? = null,
    val capabilityMessage: String? = null,
    val previewUri: String? = null,
    val jobMessage: String? = null,
    val errorMessage: String? = null,
    val initialRequest: String? = null,
    val pendingChatMessage: String? = null,
    val pendingClarification: ClarificationQuestion? = null,
    val isProcessing: Boolean = false,
    val isExporting: Boolean = false,
    val exportProgress: Float = 0.0f,
    val pastChats: List<ChatSummary> = emptyList(),
    val isLoadingPastChats: Boolean = false,
    val messages: List<UiChatMessage> = emptyList()
) {
    val source: SourceMedia? get() = sources.firstOrNull()
}
