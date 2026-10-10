package dev.claudiverse.app.ui

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.claudiverse.app.seat.TranscriptImages
import dev.claudiverse.core.TranscriptImage

/** A picture's load state: still loading, loaded, or failed with a reason. */
private sealed interface Loaded {
    data object Loading : Loaded
    data class Ready(val bitmap: Bitmap) : Loaded
    data class Failed(val why: String) : Loaded
}

@Composable
private fun rememberLoaded(image: TranscriptImage, images: TranscriptImages): Loaded =
    produceState<Loaded>(images.cached(image)?.let { Loaded.Ready(it) } ?: Loaded.Loading, image) {
        if (value is Loaded.Ready) return@produceState
        value = runCatching { images.load(image) }.fold({ Loaded.Ready(it) }, { Loaded.Failed(it.message ?: "not loaded") })
    }.value

/** The pictures of one message, under its text; a tap opens the viewer at that picture. */
@Composable
fun ImageThumbs(list: List<TranscriptImage>, images: TranscriptImages, onOpen: (TranscriptImage) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        list.forEach { image ->
            val shape = RoundedCornerShape(8.dp)
            when (val l = rememberLoaded(image, images)) {
                is Loaded.Ready -> Image(
                    l.bitmap.asImageBitmap(),
                    contentDescription = "Image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.heightIn(max = 220.dp).widthIn(max = 260.dp).clip(shape).clickable { onOpen(image) },
                )
                Loaded.Loading -> Box(
                    Modifier.size(120.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(Modifier.size(24.dp)) }
                is Loaded.Failed -> Row(
                    Modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Filled.BrokenImage, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Image not loaded: ${l.why}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * Every picture of the transcript over the screen on a dark backdrop, as a
 * chat app shows them: swipe between them, pinch or double-tap to zoom and
 * drag to pan; save one to the phone's Pictures. A tap on the backdrop, the
 * close button or Back closes it.
 */
@Composable
fun ImageViewer(
    all: List<TranscriptImage>, start: Int, images: TranscriptImages,
    /** Per picture, what it came from (a tool call), or null: shown beside the count. */
    captions: List<String?> = emptyList(),
    onClose: () -> Unit,
) {
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (all.size - 1).coerceAtLeast(0))) { all.size }
    var zoomed by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.94f))) {
            // A zoomed picture takes the drags itself; swiping to the next one waits until it is back at fit.
            HorizontalPager(pager, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize()) { page ->
                ZoomableImage(all[page], images, onZoomed = { if (page == pager.currentPage) zoomed = it }, onTapOutside = onClose)
            }
            // A scrim under the bar keeps it readable over a zoomed picture.
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White) }
                Text(
                    listOfNotNull("${pager.currentPage + 1} of ${all.size}", captions.getOrNull(pager.currentPage)).joinToString(" · "),
                    color = Color.White, style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) IconButton(onClick = {
                    val bitmap = images.cached(all[pager.currentPage])
                    val saved = bitmap != null && savePicture(context, bitmap)
                    Toast.makeText(context, if (saved) "Saved to Pictures" else "Not saved", Toast.LENGTH_SHORT).show()
                }) { Icon(Icons.Filled.Download, contentDescription = "Save", tint = Color.White) }
            }
        }
    }
}

@Composable
private fun ZoomableImage(image: TranscriptImage, images: TranscriptImages, onZoomed: (Boolean) -> Unit, onTapOutside: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    fun set(s: Float, o: Offset) {
        scale = s.coerceIn(1f, 6f)
        // Pan no further than the zoomed image's edge reaching the screen's.
        val maxX = box.width * (scale - 1f) / 2f
        val maxY = box.height * (scale - 1f) / 2f
        offset = if (scale == 1f) Offset.Zero else Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
        onZoomed(scale > 1f)
    }
    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { box = it }
            .pointerInput(image) {
                detectTapGestures(
                    onDoubleTap = { tap -> if (scale > 1f) set(1f, Offset.Zero) else set(2.5f, (Offset(size.width / 2f, size.height / 2f) - tap) * 1.5f) },
                    onTap = { if (scale == 1f) onTapOutside() },
                )
            }
            .pointerInput(image) {
                // Pinch, or drag while zoomed. At fit a one-finger drag is left
                // unconsumed for the pager: detectTransformGestures would claim it.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pinching = event.changes.count { it.pressed } >= 2
                        if (pinching || scale > 1f) {
                            set(scale * event.calculateZoom(), offset + event.calculatePan())
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when (val l = rememberLoaded(image, images)) {
            is Loaded.Ready -> Image(
                l.bitmap.asImageBitmap(),
                contentDescription = "Image",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
            )
            Loaded.Loading -> CircularProgressIndicator()
            is Loaded.Failed -> Text("Image not loaded: ${l.why}", color = Color.White)
        }
    }
}

/** Into Pictures/Claudiverse through MediaStore: no storage permission needed from Android 10. */
private fun savePicture(context: android.content.Context, bitmap: Bitmap): Boolean = runCatching {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "claudiverse-${System.currentTimeMillis()}.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Claudiverse")
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: return false
    true
}.getOrDefault(false)
