package ai.trimm.mobile.data

import ai.trimm.mobile.domain.MobileProject
import ai.trimm.mobile.domain.CreatorPreferenceProfile
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class ChatSummary(val id: String, val title: String)

class ProjectStore(context: Context) {
    private val root = File(context.filesDir, "Projects").apply {
        if (!exists()) {
            val legacy = File(context.filesDir, "projects")
            if (legacy.exists() && legacy.isDirectory) legacy.renameTo(this)
            if (!exists()) mkdirs()
        }
    }
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; classDiscriminator = "type" }
    private val creatorProfile = File(context.filesDir, "creator-preferences.json")

    suspend fun save(project: MobileProject) = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(root, "${project.id}.json")
            val content = json.encodeToString(MobileProject.serializer(), project)
            val tmp = File(root, "${file.name}.tmp")
            tmp.writeText(content)
            tmp.renameTo(file)
        }
        Unit
    }

    suspend fun load(id: String): MobileProject = withContext(Dispatchers.IO) {
        val file = File(root, "$id.json")
        json.decodeFromString(MobileProject.serializer(), file.readText())
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        runCatching {
            File(root, "$id.json").delete()
            File(root.parentFile, "${id}_composition.json").delete()
            File(root.parentFile, "${id}_initial_composition.json").delete()
        }
        Unit
    }

    suspend fun listAll(): List<ChatSummary> = withContext(Dispatchers.IO) {
        val files = root.listFiles { _, name -> name.endsWith(".json") && !name.endsWith(".tmp") } ?: emptyArray()
        files.sortedByDescending { it.lastModified() }.mapNotNull { file ->
            runCatching {
                val text = file.readText()
                val obj = json.parseToJsonElement(text).jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: file.nameWithoutExtension
                val title = obj["title"]?.jsonPrimitive?.content ?: "Untitled Chat"
                ChatSummary(id = id, title = title)
            }.getOrNull()
        }
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
