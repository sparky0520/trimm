package ai.ctrlb.mobile.data

import ai.ctrlb.mobile.domain.MobileProject
import ai.ctrlb.mobile.domain.CreatorPreferenceProfile
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ProjectStore(context: Context) {
    private val root = File(context.filesDir, "projects").apply { mkdirs() }
    private val exportsDir = File(context.filesDir, "exports")
    private val previewsDir = File(context.cacheDir, "previews")
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; classDiscriminator = "type" }
    private val creatorProfile = File(context.filesDir, "creator-preferences.json")

    suspend fun save(project: MobileProject) = withContext(Dispatchers.IO) {
        val target = File(root, "${project.id}.json")
        val temporary = File(root, "${project.id}.partial")
        val backup = File(root, "${project.id}.backup")
        temporary.writeText(json.encodeToString(MobileProject.serializer(), project))
        if (target.exists()) target.copyTo(backup, overwrite = true)
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        prune()
    }

    private fun prune() {
        val files = root.listFiles { _, name -> name.endsWith(".json") } ?: return
        if (files.size <= 20) return
        val toDelete = files.sortedByDescending { it.lastModified() }.drop(20)
        toDelete.forEach { file ->
            val id = file.nameWithoutExtension
            file.delete()
            File(root, "$id.backup").delete()
            File(root, "$id.partial").delete()
            File(exportsDir, "$id.mp4").delete()
            previewsDir.listFiles { _, name -> name.startsWith("$id-") }?.forEach { it.delete() }
        }
    }

    suspend fun load(id: String): MobileProject = withContext(Dispatchers.IO) {
        val target = File(root, "$id.json")
        runCatching { json.decodeFromString(MobileProject.serializer(), target.readText()) }.getOrElse {
            val backup = File(root, "$id.backup")
            if (!backup.exists()) throw it
            json.decodeFromString(MobileProject.serializer(), backup.readText())
        }
    }

    suspend fun listAll(): List<MobileProject> = withContext(Dispatchers.IO) {
        val files = root.listFiles { _, name -> name.endsWith(".json") } ?: return@withContext emptyList()
        files.sortedByDescending { it.lastModified() }.take(20).mapNotNull { file ->
            runCatching { json.decodeFromString(MobileProject.serializer(), file.readText()) }.getOrNull()
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
