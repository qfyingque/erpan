package com.huigu.phone10.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import kotlin.math.max

/** Only opens the URI the user selected. A small private copy survives updates, never cloud backup. */
class Phone10AvatarStore(private val context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "phone10-avatar.png"))

    fun load(): Bitmap? = try {
        file.openRead().use { BitmapFactory.decodeStream(it) }
    } catch (_: IOException) { null }

    fun import(uri: Uri): Bitmap {
        val bitmap = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val scale = 512.0 / max(info.size.width, info.size.height).coerceAtLeast(512)
                decoder.setTargetSize(max(1, (info.size.width * scale).toInt()), max(1, (info.size.height * scale).toInt()))
            }
        } else {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            if (options.outWidth <= 0 || options.outHeight <= 0) throw IOException("Invalid image")
            options.inJustDecodeBounds = false
            options.inSampleSize = 1
            while (max(options.outWidth, options.outHeight) / options.inSampleSize > 512) options.inSampleSize *= 2
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: throw IOException("Cannot open image")
        }
        val output = file.startWrite()
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("Cannot save image")
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
        return bitmap
    }
}
