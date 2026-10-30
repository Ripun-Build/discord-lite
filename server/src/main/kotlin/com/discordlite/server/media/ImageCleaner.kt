package com.discordlite.server.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.max
import kotlin.math.roundToInt

class CleanImage(val bytes: ByteArray, val width: Int, val height: Int)

/**
 * Everything people upload goes through here. Decoding a photo and saving it again as a plain JPEG
 * drops any leftover metadata (location, camera), shrinks huge photos, and means nothing but a real
 * picture is ever stored or served.
 */
object ImageCleaner {
    const val MAX_UPLOAD_BYTES = 12 * 1024 * 1024
    private const val MAX_SIDE = 1920
    private const val MAX_PIXELS = 50_000_000L

    /** Decodes any common image format and writes a fresh, metadata-free JPEG (max 1920 px). */
    suspend fun clean(input: ByteArray): CleanImage? = withContext(Dispatchers.Default) {
        runCatching {
            ImageIO.createImageInputStream(ByteArrayInputStream(input)).use { stream ->
                val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: return@runCatching null
                try {
                    reader.input = stream
                    // Check the size before decoding, so a tiny file claiming to be enormous can't eat memory.
                    val w = reader.getWidth(0)
                    val h = reader.getHeight(0)
                    if (w <= 0 || h <= 0 || w.toLong() * h > MAX_PIXELS) return@runCatching null
                    val source = reader.read(0)
                    val scale = minOf(1.0, MAX_SIDE.toDouble() / max(w, h))
                    val outW = max(1, (w * scale).roundToInt())
                    val outH = max(1, (h * scale).roundToInt())
                    val out = BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB)
                    out.createGraphics().apply {
                        setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                        drawImage(source, 0, 0, outW, outH, java.awt.Color.BLACK, null)
                        dispose()
                    }
                    val bytes = ByteArrayOutputStream()
                    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
                    ImageIO.createImageOutputStream(bytes).use { output ->
                        writer.output = output
                        val param = writer.defaultWriteParam.apply {
                            compressionMode = ImageWriteParam.MODE_EXPLICIT
                            compressionQuality = 0.85f
                        }
                        writer.write(null, IIOImage(out, null, null), param)
                    }
                    writer.dispose()
                    CleanImage(bytes.toByteArray(), outW, outH)
                } finally {
                    reader.dispose()
                }
            }
        }.getOrNull()
    }
}
