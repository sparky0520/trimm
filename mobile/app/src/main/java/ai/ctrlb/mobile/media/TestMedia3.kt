package ai.ctrlb.mobile.media
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
fun test(url: String) {
    runBlocking(Dispatchers.IO) {
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(url, HashMap<String, String>())
        val duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 15000L
        mmr.release()
    }
}
