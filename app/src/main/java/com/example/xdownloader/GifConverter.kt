package com.example.xdownloader

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.util.Arrays

/**
 * Convierte un MP4 en un GIF animado sin librerías externas:
 * extrae fotogramas con MediaMetadataRetriever, genera una paleta de 256 colores
 * (median cut) y escribe el GIF en streaming (poca memoria).
 */
object GifConverter {
    private const val MAX_WIDTH = 480          // ancho máximo del GIF
    private const val MAX_SECONDS = 20         // duración máxima convertida
    private const val STEP_US = 70_000L        // un fotograma cada 70 ms (~14 fps)
    private const val DELAY_CS = 7             // 7 centésimas de segundo por fotograma
    private const val PALETTE_FRAMES = 12      // fotogramas usados para calcular la paleta
    private const val PIXELS_PER_FRAME = 6000  // píxeles muestreados por fotograma

    fun convert(path: String, out: OutputStream, onProgress: (Int) -> Unit) {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(path)
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            var vw = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            var vh = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) {
                val t = vw; vw = vh; vh = t
            }
            if (vw <= 0 || vh <= 0) throw Exception("No se pudo leer el vídeo.")

            val scale = minOf(1.0, MAX_WIDTH.toDouble() / vw)
            val w = maxOf(1, (vw * scale).toInt())
            val h = maxOf(1, (vh * scale).toInt())

            val usedUs = maxOf(minOf(durMs, MAX_SECONDS * 1000L) * 1000L, STEP_US)
            val frames = maxOf(1, (usedUs / STEP_US).toInt())

            // ---- Paso 1: paleta global a partir de unos cuantos fotogramas ----
            val sampleCount = minOf(PALETTE_FRAMES, frames)
            val samples = IntArray(sampleCount * PIXELS_PER_FRAME)
            var n = 0
            val px = IntArray(w * h)
            for (s in 0 until sampleCount) {
                val idx = if (sampleCount == 1) 0 else s * (frames - 1) / (sampleCount - 1)
                val bmp = grab(mmr, idx * STEP_US, w, h) ?: continue
                bmp.getPixels(px, 0, w, 0, 0, w, h)
                bmp.recycle()
                val stride = maxOf(1, px.size / PIXELS_PER_FRAME)
                var p = 0
                while (p < px.size && n < samples.size) {
                    samples[n++] = px[p] and 0xFFFFFF
                    p += stride
                }
            }
            if (n == 0) throw Exception("No se pudieron leer fotogramas del vídeo.")
            val palette = medianCut(samples, n)
            val quant = Quantizer(palette)

            // ---- Paso 2: codificar fotograma a fotograma ----
            val enc = GifEncoder(BufferedOutputStream(out, 65536), w, h, palette, DELAY_CS)
            val idxBuf = ByteArray(w * h)
            var haveFrame = false
            for (i in 0 until frames) {
                val bmp = grab(mmr, i * STEP_US, w, h)
                if (bmp != null) {
                    bmp.getPixels(px, 0, w, 0, 0, w, h)
                    bmp.recycle()
                    for (j in px.indices) idxBuf[j] = quant.index(px[j]).toByte()
                    haveFrame = true
                } else if (!haveFrame) {
                    continue // aún no hay ningún fotograma válido
                } // si falla uno intermedio se repite el anterior
                enc.writeFrame(idxBuf)
                onProgress((i + 1) * 100 / frames)
            }
            if (!haveFrame) throw Exception("No se pudieron leer fotogramas del vídeo.")
            enc.finish()
        } finally {
            mmr.release()
        }
    }

    private fun grab(mmr: MediaMetadataRetriever, tUs: Long, w: Int, h: Int): Bitmap? {
        val b = mmr.getScaledFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST, w, h)
            ?: return null
        if (b.width == w && b.height == h) return b
        val scaled = Bitmap.createScaledBitmap(b, w, h, true)
        if (scaled !== b) b.recycle()
        return scaled
    }

    // ---------------- Paleta (median cut) ----------------

    private fun channel(p: Int, ch: Int): Int = when (ch) {
        0 -> (p shr 16) and 255
        1 -> (p shr 8) and 255
        else -> p and 255
    }

    private fun medianCut(samples: IntArray, n: Int): ByteArray {
        val bs = IntArray(256)
        val be = IntArray(256)
        var nb = 1
        bs[0] = 0
        be[0] = n
        while (nb < 256) {
            var best = -1
            var bestScore = 0L
            var bestCh = 0
            for (b in 0 until nb) {
                val size = be[b] - bs[b]
                if (size < 2) continue
                val mn = intArrayOf(255, 255, 255)
                val mx = intArrayOf(0, 0, 0)
                for (k in bs[b] until be[b]) {
                    val p = samples[k]
                    for (c in 0..2) {
                        val v = channel(p, c)
                        if (v < mn[c]) mn[c] = v
                        if (v > mx[c]) mx[c] = v
                    }
                }
                var ch = 0
                var range = mx[0] - mn[0]
                for (c in 1..2) {
                    if (mx[c] - mn[c] > range) {
                        range = mx[c] - mn[c]
                        ch = c
                    }
                }
                val score = range.toLong() * size
                if (range > 0 && score > bestScore) {
                    bestScore = score
                    best = b
                    bestCh = ch
                }
            }
            if (best < 0) break
            val s = bs[best]
            val e = be[best]
            val tmp = LongArray(e - s) {
                (channel(samples[s + it], bestCh).toLong() shl 32) or
                    (samples[s + it].toLong() and 0xFFFFFFFFL)
            }
            tmp.sort()
            for (k in tmp.indices) samples[s + k] = tmp[k].toInt()
            val mid = s + (e - s) / 2
            bs[nb] = mid
            be[nb] = e
            be[best] = mid
            nb++
        }
        val pal = ByteArray(768)
        for (b in 0 until nb) {
            var r = 0L; var g = 0L; var bl = 0L
            val size = be[b] - bs[b]
            for (k in bs[b] until be[b]) {
                val p = samples[k]
                r += (p shr 16) and 255
                g += (p shr 8) and 255
                bl += p and 255
            }
            if (size > 0) {
                pal[b * 3] = (r / size).toInt().toByte()
                pal[b * 3 + 1] = (g / size).toInt().toByte()
                pal[b * 3 + 2] = (bl / size).toInt().toByte()
            }
        }
        return pal
    }

    private class Quantizer(private val pal: ByteArray) {
        private val cache = IntArray(32768) { -1 }

        fun index(rgb: Int): Int {
            val r = (rgb shr 16) and 255
            val g = (rgb shr 8) and 255
            val b = rgb and 255
            val key = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
            val cached = cache[key]
            if (cached >= 0) return cached
            var best = 0
            var bd = Int.MAX_VALUE
            for (k in 0 until 256) {
                val dr = r - (pal[k * 3].toInt() and 255)
                val dg = g - (pal[k * 3 + 1].toInt() and 255)
                val db = b - (pal[k * 3 + 2].toInt() and 255)
                val d = 3 * dr * dr + 4 * dg * dg + 2 * db * db
                if (d < bd) {
                    bd = d
                    best = k
                }
            }
            cache[key] = best
            return best
        }
    }

    // ---------------- Codificador GIF89a ----------------

    private class GifEncoder(
        private val out: OutputStream,
        private val w: Int,
        private val h: Int,
        palette: ByteArray,
        private val delayCs: Int
    ) {
        private val block = ByteArray(255)
        private var blockLen = 0
        private var bitBuf = 0
        private var bitCnt = 0
        private var codeSize = 9
        private val hKeys = IntArray(HSIZE)
        private val hVals = IntArray(HSIZE)

        init {
            out.write("GIF89a".toByteArray(Charsets.US_ASCII))
            writeShort(w)
            writeShort(h)
            out.write(0xF7)   // paleta global de 256 colores
            out.write(0)      // color de fondo
            out.write(0)      // relación de aspecto
            out.write(palette)
            // Extensión NETSCAPE: repetir en bucle infinito
            out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
            out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
            out.write(byteArrayOf(3, 1, 0, 0, 0))
        }

        private fun writeShort(v: Int) {
            out.write(v and 255)
            out.write((v shr 8) and 255)
        }

        fun writeFrame(pix: ByteArray) {
            // Control gráfico: método de descarte 1, retardo del fotograma
            out.write(
                byteArrayOf(
                    0x21, 0xF9.toByte(), 4, 0x04,
                    (delayCs and 255).toByte(), ((delayCs shr 8) and 255).toByte(), 0, 0
                )
            )
            out.write(0x2C)
            writeShort(0)
            writeShort(0)
            writeShort(w)
            writeShort(h)
            out.write(0)
            writeLzw(pix)
        }

        fun finish() {
            out.write(0x3B)
            out.flush()
        }

        private fun emitByte(b: Int) {
            block[blockLen++] = b.toByte()
            if (blockLen == 255) flushBlock()
        }

        private fun flushBlock() {
            if (blockLen > 0) {
                out.write(blockLen)
                out.write(block, 0, blockLen)
                blockLen = 0
            }
        }

        private fun putCode(code: Int) {
            bitBuf = bitBuf or (code shl bitCnt)
            bitCnt += codeSize
            while (bitCnt >= 8) {
                emitByte(bitBuf and 255)
                bitBuf = bitBuf ushr 8
                bitCnt -= 8
            }
        }

        private fun writeLzw(pix: ByteArray) {
            val clear = 256
            val eoi = 257
            var next = 258
            out.write(8) // tamaño mínimo de código
            codeSize = 9
            bitBuf = 0
            bitCnt = 0
            Arrays.fill(hKeys, -1)
            putCode(clear)

            var prefix = pix[0].toInt() and 255
            for (i in 1 until pix.size) {
                val k = pix[i].toInt() and 255
                val key = (prefix shl 8) or k
                var hh = (key * -1640531535) ushr (32 - HBITS)
                var found = -1
                while (true) {
                    val kk = hKeys[hh]
                    if (kk == -1) break
                    if (kk == key) {
                        found = hVals[hh]
                        break
                    }
                    hh = (hh + 1) and (HSIZE - 1)
                }
                if (found >= 0) {
                    prefix = found
                    continue
                }
                putCode(prefix)
                if (next == (1 shl codeSize) && codeSize < 12) codeSize++
                if (next < 4096) {
                    hKeys[hh] = key
                    hVals[hh] = next
                    next++
                } else {
                    putCode(clear)
                    Arrays.fill(hKeys, -1)
                    next = 258
                    codeSize = 9
                }
                prefix = k
            }
            putCode(prefix)
            if (next == (1 shl codeSize) && codeSize < 12) codeSize++
            putCode(eoi)
            if (bitCnt > 0) {
                emitByte(bitBuf and 255)
                bitBuf = 0
                bitCnt = 0
            }
            flushBlock()
            out.write(0) // fin de los datos de imagen
        }

        companion object {
            private const val HBITS = 14
            private const val HSIZE = 1 shl HBITS
        }
    }
}
