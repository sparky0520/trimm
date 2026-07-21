package ai.ctrlb.mobile.data

import ai.ctrlb.mobile.domain.MobileProject
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ProjectStore(context: Context) {
    private val root = File(context.filesDir, "projects").apply { mkdirs() }
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; classDiscriminator = "type" }

    suspend fun save(project: MobileProject) = withContext(Dispatchers.IO) {
        val target = File(root, "${project.id}.json")
        val temporary = File(root, "${project.id}.partial")
        val backup = File(root, "${project.id}.backup")
        temporary.writeText(json.encodeToString(MobileProject.serializer(), project))
        if (target.exists()) target.copyTo(backup, overwrite = true)
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    suspend fun load(id: String): MobileProject = withContext(Dispatchers.IO) {
        val target = File(root, "$id.json")
        runCatching { json.decodeFromString(MobileProject.serializer(), target.readText()) }.getOrElse {
            val backup = File(root, "$id.backup")
            if (!backup.exists()) throw it
            json.decodeFromString(MobileProject.serializer(), backup.readText())
        }
    }
}
