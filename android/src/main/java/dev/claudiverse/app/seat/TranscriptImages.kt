package dev.claudiverse.app.seat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import dev.claudiverse.core.Api
import dev.claudiverse.core.TranscriptImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.asSink
import kotlinx.io.buffered
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Pictures in transcripts, decoded once and kept by their file url: a stored
 * file never changes, so a url is a stable key. Inline base64 (the fallback
 * when the server has not moved an image to its file store) is keyed by its
 * content. Large images are decoded at a reduced size: a retina screenshot at
 * full resolution is ~25 MB of bitmap, and the phone shows it no larger than
 * its screen.
 */
class TranscriptImages(private val api: Api) {

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun cached(image: TranscriptImage): Bitmap? = cache.get(key(image))

    /** The image, from the cache or loaded now. Throws when it cannot be fetched or is not an image. */
    suspend fun load(image: TranscriptImage): Bitmap = cached(image) ?: withContext(Dispatchers.IO) {
        val bytes = image.base64?.let { Base64.decode(it, Base64.DEFAULT) }
            ?: image.url?.let { url ->
                val out = ByteArrayOutputStream()
                out.asSink().buffered().use { sink -> api.download(url, sink) { } }
                out.toByteArray()
            }
            ?: throw IOException("the image has neither a url nor data")
        decode(bytes).also { cache.put(key(image), it) }
    }

    private fun decode(bytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("not an image")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("not an image")
    }

    private fun key(image: TranscriptImage): String =
        image.url ?: "inline:${image.base64?.length}:${image.base64?.hashCode()}"

    private companion object {
        const val CACHE_BYTES = 48 * 1024 * 1024
        /** Decode no smaller than this on the long side; a phone screen is ~2400 px. */
        const val MAX_SIDE = 2400
    }
}
