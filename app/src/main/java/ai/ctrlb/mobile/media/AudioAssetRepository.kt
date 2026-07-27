package ai.ctrlb.mobile.media

import ai.ctrlb.mobile.BuildConfig
import ai.ctrlb.mobile.domain.*
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.security.MessageDigest

@Serializable private data class CatalogDocument(val version: Int, val items: List<AudioCatalogItem>)

class AudioAssetRepository(private val context: Context) {
    private val root = File(context.filesDir, "audio-catalog").apply { mkdirs() }
    private val catalogCache = File(root, "catalog.v1.json")
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun prepare(project: MobileProject): MobileProject = withContext(Dispatchers.IO) {
        val needsAudio = project.operations.any { it.enabled && (it is SetBackgroundMusic || it is AddSoundEffect) }
        if (!needsAudio) return@withContext project
        val catalog = loadCatalog()
        val storyContext = StoryAudioContext.from(project.analysis.storyGraph, project.durationMs)
        val rankedMusic = AudioMatcher.rankMusic(catalog, storyContext, catalogVersion = "catalog-v1")
        val operations = project.operations.map { operation ->
            when (operation) {
                is SetBackgroundMusic -> {
                    val selected = if (operation.catalogId == "automatic") rankedMusic.firstOrNull()?.item
                        ?: error("No licensed background music matches this edit.")
                    else catalog.firstOrNull { it.id == operation.catalogId && it.kind == AudioKind.MUSIC }
                        ?: error("Background music '${operation.catalogId}' is not in the licensed catalog.")
                    ensureDownloaded(selected)
                    operation.copy(
                        catalogId = selected.id,
                        loop = false,
                        structure = operation.structure ?: StoryAudioStructurePlanner.plan(selected, storyContext)
                    )
                }
                is AddSoundEffect -> {
                    val selected = catalog.firstOrNull { it.id == operation.catalogId && it.kind == AudioKind.SOUND_EFFECT }
                        ?: error("Sound effect '${operation.catalogId}' is not in the licensed catalog.")
                    ensureDownloaded(selected); operation
                }
                else -> operation
            }
        }
        project.copy(operations = operations, revision = project.revision + if (operations != project.operations) 1 else 0)
    }

    fun localFile(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9._-]+"))) { "Invalid audio asset id" }
        return File(root, "$id.media").also { require(it.isFile) { "Audio asset '$id' has not been downloaded." } }
    }

    private fun loadCatalog(): List<AudioCatalogItem> {
        check(BuildConfig.AUDIO_CATALOG_URL.startsWith("https://")) { "Licensed audio catalog HTTPS URL is not configured." }
        val document = try {
            val connection = URI(BuildConfig.AUDIO_CATALOG_URL).toURL().openConnection().apply { connectTimeout = 15_000; readTimeout = 30_000 }
            val downloaded = connection.getInputStream().bufferedReader().use { it.readText() }
            decodeCatalog(downloaded).also { cacheCatalog(downloaded) }
        } catch (error: Throwable) {
            if (!catalogCache.isFile) throw error
            decodeCatalog(catalogCache.readText())
        }
        return document.items.filter {
            it.status == "ready" && it.quarantineReasons.isEmpty() &&
                it.provider.isNotBlank() && it.providerAssetId.isNotBlank() && it.acquiredAt.isNotBlank() &&
                "rendered-video" in it.allowedUse &&
                (it.licenseId.isNotBlank() || !it.licenseText.isNullOrBlank()) &&
                it.checksum.matches(Regex("[a-fA-F0-9]{64}")) &&
                (!it.attributionRequired || !it.attribution.isNullOrBlank()) &&
                listOfNotNull(it.downloadUrl, it.previewUrl, it.sourceUrl).all { url -> url.startsWith("https://") }
        }
    }

    private fun decodeCatalog(body: String): CatalogDocument =
        json.decodeFromString<CatalogDocument>(body).also {
            check(it.version == 1) { "Unsupported audio catalog version ${it.version}." }
        }

    private fun cacheCatalog(body: String) {
        val partial = File(root, "catalog.v1.partial")
        partial.writeText(body)
        check(partial.renameTo(catalogCache) || run {
            catalogCache.delete()
            partial.renameTo(catalogCache)
        }) { partial.delete(); "Could not cache the licensed audio catalog." }
    }

    private fun ensureDownloaded(item: AudioCatalogItem): File {
        val target = File(root, "${item.id}.media")
        if (target.isFile && sha256(target).equals(item.checksum, true)) return target
        target.delete()
        val url = requireNotNull(item.downloadUrl) { "Audio asset '${item.id}' has no download URL." }
        check(url.startsWith("https://")) { "Audio downloads must use HTTPS." }
        val partial = File(root, "${item.id}.partial").apply { delete() }
        try { URI(url).toURL().openStream().use { input -> partial.outputStream().use(input::copyTo) } }
        catch (error: Throwable) { partial.delete(); throw error }
        check(sha256(partial).equals(item.checksum, true)) { partial.delete(); "Audio checksum mismatch for '${item.id}'." }
        check(partial.renameTo(target)) { partial.delete(); "Could not install audio asset '${item.id}'." }
        return target
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(DEFAULT_BUFFER_SIZE); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
