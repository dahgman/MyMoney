package com.martinsmayhem.mymoney.mobile.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.martinsmayhem.mymoney.mobile.ReceiptImage
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Makes a photo ready to send as a receipt (docs/SYNC-DESIGN.md, "Receipt
 * photos"): upright (as the camera held it), at most 1600 pixels on its long
 * side, a JPEG at quality 80 (some 200 to 400 KB), and a 240-pixel thumbnail.
 */
object ReceiptImages {

    private const val LONG_SIDE = 1600
    private const val THUMB_SIDE = 240

    fun prepare(context: Context, uri: Uri): ReceiptImage? {
        val resolver = context.contentResolver
        // How big it is, to read it at about the size needed (not a 50-megapixel bitmap).
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val stream = resolver.openInputStream(uri) ?: return null
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }     // fills in bounds only (returns null)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= LONG_SIDE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val upright = rotate(decoded, orientation(context, uri))
        val photo = scale(upright, LONG_SIDE)
        val thumb = scale(photo, THUMB_SIDE)
        return ReceiptImage(jpeg(photo, 80), jpeg(thumb, 70))
    }

    private fun orientation(context: Context, uri: Uri): Int = try {
        context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun rotate(b: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            else -> return b
        }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    private fun scale(b: Bitmap, longSide: Int): Bitmap {
        val longest = max(b.width, b.height)
        if (longest <= longSide) return b
        val f = longSide.toFloat() / longest
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
    }

    private fun jpeg(b: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
}
