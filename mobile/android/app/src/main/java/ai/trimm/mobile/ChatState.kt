package ai.trimm.mobile

import ai.trimm.mobile.domain.*
import ai.trimm.mobile.data.ProjectStore
import ai.trimm.mobile.media.DeviceCapability
import ai.trimm.mobile.media.AndroidDerivativeWriter
import ai.trimm.mobile.media.SelectiveDerivativePreparer
import ai.trimm.mobile.media.DerivativeAuthorization
import ai.trimm.mobile.auth.AuthTokenProvider
import ai.trimm.mobile.planner.*
import ai.trimm.mobile.work.AnalysisWorker
import ai.trimm.mobile.work.ExportWorker
import ai.trimm.mobile.work.PreviewWorker
import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.ExistingWorkPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ai.trimm.mobile.network.ChatApiService
import ai.trimm.mobile.network.ChatRepository
import ai.trimm.mobile.network.supabase
import io.github.jan.supabase.storage.storage
import java.security.MessageDigest

data class ChatState(
    val sources: List<SourceMedia> = emptyList(),
    val preferences: WizardPreferences = WizardPreferences(),
    val creatorPreferences: CreatorPreferenceProfile = CreatorPreferenceProfile(),
    val project: MobileProject? = null,
    val capabilityMessage: String? = null,
    val previewUri: String? = null,
    val jobMessage: String? = null,
    val errorMessage: String? = null,
    val initialRequest: String? = null,
    val pendingChatMessage: String? = null,
    val isProcessing: Boolean = false,
    val pastChats: List<ai.trimm.mobile.data.ChatSummary> = emptyList(),
    val isLoadingPastChats: Boolean = false
) {
    val source: SourceMedia? get() = sources.firstOrNull()
}
