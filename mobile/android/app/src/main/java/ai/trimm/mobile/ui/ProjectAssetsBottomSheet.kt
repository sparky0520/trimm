package ai.trimm.mobile.ui

import ai.trimm.mobile.domain.SourceMedia
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectAssetsBottomSheet(
    sources: List<SourceMedia>,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Ink,
        contentColor = Cream,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Project Assets",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Cream
                )

                TextButton(onClick = onDismiss) {
                    Text(
                        text = "Done",
                        color = Coral,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                }
            }

            HorizontalDivider(color = Border.copy(alpha = 0.4f))

            if (sources.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.VideocamOff,
                            contentDescription = null,
                            tint = Lavender.copy(alpha = 0.6f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "No Project Assets Found",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Cream
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Imported video clips and audio tracks will appear here.",
                            fontSize = 14.sp,
                            color = Muted,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(sources, key = { index, s -> "${s.uri}-$index" }) { index, source ->
                        AssetRowItem(source = source, index = index)
                    }
                }
            }
        }
    }
}

@Composable
fun AssetRowItem(
    source: SourceMedia,
    index: Int
) {
    val context = LocalContext.current
    var thumbnailBitmap by remember(source.uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(source.uri) {
        withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(source.uri)
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, uri)
                val targetUs = minOf(1_000_000L, maxOf(100_000L, source.durationMs * 500L))
                val frame = retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                retriever.release()
                frame
            }.getOrNull()?.let {
                thumbnailBitmap = it
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Workspace)
            .border(1.dp, Border.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Raised)
                .border(1.dp, Border.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            val bitmap = thumbnailBitmap
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Clip thumbnail",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Movie,
                    contentDescription = null,
                    tint = Lavender.copy(alpha = 0.6f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(Modifier.width(14.dp))

        // Metadata
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Clip ${index + 1}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Cream
                )

                Box(
                    modifier = Modifier
                        .background(Coral.copy(alpha = 0.15f), CircleShape)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = formatAssetDuration(source.durationMs),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Coral
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (source.width > 0 && source.height > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AspectRatio,
                            contentDescription = null,
                            tint = Muted,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "${source.width}×${source.height}",
                            fontSize = 12.sp,
                            color = Muted
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (source.hasAudio) Icons.Default.VolumeUp else Icons.Default.VolumeMute,
                        contentDescription = null,
                        tint = if (source.hasAudio) Muted else Color.Gray,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = if (source.hasAudio) "Audio" else "Muted",
                        fontSize = 12.sp,
                        color = if (source.hasAudio) Muted else Color.Gray
                    )
                }
            }
        }
    }
}

private fun formatAssetDuration(ms: Long): String {
    val totalSeconds = maxOf(1L, ms / 1000L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes > 0) {
        "%d:%02d".format(minutes, seconds)
    } else {
        "${seconds}s"
    }
}
