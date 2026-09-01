package ai.ctrlb.mobile.data

import ai.ctrlb.mobile.domain.MobileProject
import ai.ctrlb.mobile.domain.CreatorPreferenceProfile
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class ChatSummary(val id: String, val title: String)

class ProjectStore(context: Context) {
    private val root = File(context.filesDir, "projects").apply { mkdirs() }
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; classDiscriminator = "type" }
    private val creatorProfile = File(context.filesDir, "creator-preferences.json")

    private val apiService by lazy { ai.ctrlb.mobile.network.ChatApiService.create() }

    suspend fun save(project: MobileProject) = withContext(Dispatchers.IO) {
        val projectJsonStr = json.encodeToString(MobileProject.serializer(), project)
        val target = File(root, "${project.id}.json")
        val temporary = File(root, "${project.id}.partial")
        val backup = File(root, "${project.id}.backup")
        temporary.writeText(projectJsonStr)
        if (target.exists()) target.copyTo(backup, overwrite = true)
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)

        // Background cloud sync
        GlobalScope.launch(Dispatchers.IO) {
            runCatching {
                val token = ai.ctrlb.mobile.auth.AuthTokenProvider.currentToken()
                val requestBody = okhttp3.RequestBody.create(
                    "application/json".toMediaType(),
                    projectJsonStr
                )
                apiService.syncProject("Bearer $token", requestBody)
            }
        }
        Unit
    }

    suspend fun load(id: String): MobileProject = withContext(Dispatchers.IO) {
        val target = File(root, "$id.json")
        
        // Try to fetch full project from cloud first
        runCatching {
            val token = ai.ctrlb.mobile.auth.AuthTokenProvider.currentToken()
            val response = apiService.getProject("Bearer $token", id)
            target.writeText(response.toString())
        }.onFailure {
            it.printStackTrace()
        }

        runCatching { json.decodeFromString(MobileProject.serializer(), target.readText()) }.getOrElse {
            val backup = File(root, "$id.backup")
            if (!backup.exists()) throw it
            json.decodeFromString(MobileProject.serializer(), backup.readText())
        }
    }

    suspend fun listAll(): List<ChatSummary> = withContext(Dispatchers.IO) {
        val summaries = mutableMapOf<String, ChatSummary>()
        
        // Add local projects first
        val files = root.listFiles { _, name -> name.endsWith(".json") } ?: emptyArray()
        files.sortedByDescending { it.lastModified() }.take(20).forEach { file ->
            runCatching {
                val project = json.decodeFromString(MobileProject.serializer(), file.readText())
                val cleanTitle = if (project.title == "New Edit" || project.title == "Talking head edit" || project.title.isBlank()) {
                    project.analysis.words.take(4).joinToString(" ") { it.text }.takeIf { it.isNotBlank() } ?: "Untitled Chat"
                } else {
                    project.title
                }
                summaries[project.id] = ChatSummary(project.id, cleanTitle)
            }
        }

        // Try fetching from cloud and update local cache with summaries
        runCatching {
            val token = ai.ctrlb.mobile.auth.AuthTokenProvider.currentToken()
            val response = apiService.getProjects("Bearer $token")
            val projectsArray = response.getAsJsonArray("projects")
            projectsArray.forEach { projectJson ->
                val id = projectJson.asJsonObject.get("id").asString
                val title = projectJson.asJsonObject.get("title").asString
                summaries[id] = ChatSummary(id, title)
            }
        }.onFailure {
            it.printStackTrace()
        }

        summaries.values.toList()
    }

    suspend fun saveCreatorPreferences(profile: CreatorPreferenceProfile) = withContext(Dispatchers.IO) {
        val temporary = File(creatorProfile.parentFile, "${creatorProfile.name}.partial")
        temporary.writeText(json.encodeToString(CreatorPreferenceProfile.serializer(), profile))
        Files.move(temporary.toPath(), creatorProfile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    suspend fun loadCreatorPreferences(): CreatorPreferenceProfile = withContext(Dispatchers.IO) {
        if (!creatorProfile.exists()) CreatorPreferenceProfile()
        else runCatching {
            json.decodeFromString(CreatorPreferenceProfile.serializer(), creatorProfile.readText())
        }.getOrDefault(CreatorPreferenceProfile())
    }
}
