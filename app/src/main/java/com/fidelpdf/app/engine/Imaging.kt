package com.fidelpdf.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan

object Colors {
    fun red(c: Int): Int = (c shr 16) and 255
    fun green(c: Int): Int = (c shr 8) and 255
    fun blue(c: Int): Int = c and 255

    fun rgb(r: Int, g: Int, b: Int): Int =
        (255 shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun lum(c: Int): Int = (red(c) * 299 + green(c) * 587 + blue(c) * 114) / 1000

    fun chroma(c: Int): Int {
        val r = red(c)
        val g = green(c)
        val b = blue(c)
        return max(r, max(g, b)) - min(r, min(g, b))
    }

    /** Distance moyenne (0..255) entre deux couleurs. */
    fun dist(a: Int, b: Int): Int =
        (abs(red(a) - red(b)) + abs(green(a) - green(b)) + abs(blue(a) - blue(b))) / 3
}

class PixelBuf(val w: Int, val h: Int, val px: IntArray) {
    fun lum(x: Int, y: Int): Int = Colors.lum(px[y * w + x])

    companion object {
        fun from(b: Bitmap): PixelBuf {
            val w = b.width
            val h = b.height
            val a = IntArray(w * h)
            b.getPixels(a, 0, w, 0, 0, w, h)
            return PixelBuf(w, h, a)
        }
    }
}

object Otsu {
    /** Seuil de luminance : classe 0 = valeurs <= seuil. */
    fun threshold(hist: IntArray): Int {
        var total = 0
        for (v in hist) total += v
        var sum = 0L
        for (i in 0..255) sum += i.toLong() * hist[i]
        var sumB = 0L
        var wB = 0
        var best = -1.0
        var thr = 127
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t.toLong() * hist[t]
            val mB = sumB.toDouble() / wB
            val mF = (sum - sumB).toDouble() / wF
            val between = wB.toDouble() * wF.toDouble() * (mB - mF) * (mB - mF)
            if (between > best) {
                best = between
                thr = t
            }
        }
        return thr
    }
}

object Paper {
    private fun key(c: Int): Int =
        (((c shr 19) and 31) shl 10) or (((c shr 11) and 31) shl 5) or ((c shr 3) and 31)

    /** Couleur dominante (le papier) d'un tableau de pixels. */
    fun estimate(px: IntArray, step: Int = 3): Int {
        val hist = IntArray(32768)
        var i = 0
        while (i < px.size) {
            hist[key(px[i])]++
            i += step
        }
        var best = 0
        for (k in hist.indices) if (hist[k] > hist[best]) best = k
        var sr = 0L
        var sg = 0L
        var sb = 0L
        var n = 0
        i = 0
        while (i < px.size) {
            val c = px[i]
            if (key(c) == best) {
                sr += Colors.red(c)
                sg += Colors.green(c)
                sb += Colors.blue(c)
                n++
            }
            i += step
        }
        if (n == 0) return Color.WHITE
        return Colors.rgb((sr / n).toInt(), (sg / n).toInt(), (sb / n).toInt())
    }

    fun estimateBitmap(b: Bitmap): Int {
        val s = 400f / max(b.width, b.height)
        val w = max(1, (b.width * s).roundToInt())
        val h = max(1, (b.height * s).roundToInt())
        val small = Bitmap.createScaledBitmap(b, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== b) small.recycle()
        return estimate(px, 1)
    }

    /** Un papier clair et neutre devient blanc pur (rendu net et professionnel). */
    fun output(raw: Int): Int =
        if (Colors.lum(raw) >= 200 && Colors.chroma(raw) < 30) Color.WHITE else raw
}

object Imaging {

    fun load(context: Context, uri: Uri, maxSide: Int = 3000): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalStateException("Image illisible")

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("Image illisible")

        var rotation = 0
        resolver.openInputStream(uri)?.use { stream ->
            val orientation = ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
            rotation = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }

        val longest = max(bmp.width, bmp.height)
        val scale = if (longest > maxSide) maxSide.toFloat() / longest else 1f
        if (rotation != 0 || scale < 1f) {
            val m = Matrix()
            if (scale < 1f) m.postScale(scale, scale)
            if (rotation != 0) m.postRotate(rotation.toFloat())
            val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (out !== bmp) bmp.recycle()
            bmp = out
        }
        if (bmp.config != Bitmap.Config.ARGB_8888) {
            val copy = bmp.copy(Bitmap.Config.ARGB_8888, false)
            bmp.recycle()
            bmp = copy
        }
        return bmp
    }

    /** Angle d'inclinaison du texte en degrés (positif = lignes qui descendent vers la droite). */
    fun estimateSkew(src: Bitmap): Float {
        val targetW = 700
        val s = targetW.toFloat() / src.width
        val sw = targetW
        val sh = max(8, (src.height * s).roundToInt())
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (small !== src) small.recycle()

        val hist = IntArray(256)
        val lums = IntArray(sw * sh)
        for (i in lums.indices) {
            val l = Colors.lum(px[i])
            lums[i] = l
            hist[l]++
        }
        val thr = Otsu.threshold(hist)
        var low = 0
        for (i in 0..thr) low += hist[i]
        val inkIsDark = low <= lums.size - low
        if (low == 0 || low == lums.size) return 0f

        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        val stride = max(1, (min(low, lums.size - low)) / 60000)
        var counter = 0
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                val l = lums[y * sw + x]
                val ink = if (inkIsDark) l <= thr else l > thr
                if (ink) {
                    if (counter % stride == 0) {
                        xs.add(x)
                        ys.add(y)
                    }
                    counter++
                }
            }
        }
        if (xs.size < 200) return 0f

        val off = (sw * 0.12f).toInt() + 4
        val bins = IntArray(sh + 2 * off + 2)

        fun scoreAt(deg: Float): Double {
            java.util.Arrays.fill(bins, 0)
            val t = tan(Math.toRadians(deg.toDouble())).toFloat()
            for (i in xs.indices) {
                val yy = ys[i] - (xs[i] * t).roundToInt() + off
                if (yy >= 0 && yy < bins.size) bins[yy]++
            }
            var sum = 0.0
            for (v in bins) sum += v.toDouble() * v
            return sum
        }

        val base = scoreAt(0f)
        var bestDeg = 0f
        var best = base
        var d = -6f
        while (d <= 6f) {
            val sc = scoreAt(d)
            if (sc > best) {
                best = sc
                bestDeg = d
            }
            d += 0.5f
        }
        var fine = bestDeg - 0.5f
        val fineEnd = bestDeg + 0.5f
        while (fine <= fineEnd) {
            val sc = scoreAt(fine)
            if (sc > best) {
                best = sc
                bestDeg = fine
            }
            fine += 0.1f
        }
        // Gain trop faible : on ne touche pas à l'image.
        return if (best > base * 1.03) bestDeg else 0f
    }

    fun rotate(src: Bitmap, degrees: Float, fill: Int): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(fill)
        val m = Matrix()
        m.postRotate(degrees, src.width / 2f, src.height / 2f)
        c.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }
}
