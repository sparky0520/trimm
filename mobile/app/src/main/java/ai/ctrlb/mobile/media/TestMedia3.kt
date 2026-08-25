package ai.ctrlb.mobile.media
import androidx.media3.transformer.EditedMediaItem
fun test(item: androidx.media3.common.MediaItem) {
    EditedMediaItem.Builder(item).setDurationUs(1000L).build()
}
