package ir.mmdkavir.kavirdl

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.net.Uri
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File

enum class Kind { VIDEO, AUDIO, IMAGE }

object Gallery {
    fun mimeOf(file: File): String = when (file.extension.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "opus", "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "gif" -> "image/gif"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "jpg", "jpeg" -> "image/jpeg"
        else -> "application/octet-stream"
    }

    fun kindOf(mime: String): Kind = when {
        mime.startsWith("video") -> Kind.VIDEO
        mime.startsWith("audio") -> Kind.AUDIO
        else -> Kind.IMAGE
    }

    fun saveToTree(ctx: Context, file: File, mime: String, tree: Uri) {
        val dir = DocumentFile.fromTreeUri(ctx, tree) ?: throw RuntimeException("پوشه در دسترس نیست")
        val target = dir.createFile(mime, file.name) ?: throw RuntimeException("ساخت فایل ممکن نشد")
        ctx.contentResolver.openOutputStream(target.uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    fun save(ctx: Context, file: File, mime: String, kind: Kind) {
        val folder = "KavirDL"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val (base, uri) = when (kind) {
                Kind.VIDEO -> Environment.DIRECTORY_MOVIES to MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                Kind.AUDIO -> Environment.DIRECTORY_MUSIC to MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                Kind.IMAGE -> Environment.DIRECTORY_PICTURES to MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$base/$folder")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val target = resolver.insert(uri, values) ?: throw RuntimeException("ذخیره ممکن نشد")
            try {
                resolver.openOutputStream(target)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(target, done, null, null)
            } catch (t: Throwable) {
                resolver.delete(target, null, null)
                throw t
            }
        } else {
            val base = when (kind) {
                Kind.VIDEO -> Environment.DIRECTORY_MOVIES
                Kind.AUDIO -> Environment.DIRECTORY_MUSIC
                Kind.IMAGE -> Environment.DIRECTORY_PICTURES
            }
            val dir = File(Environment.getExternalStoragePublicDirectory(base), folder)
            dir.mkdirs()
            val dest = File(dir, file.name)
            file.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), arrayOf(mime), null)
        }
    }
}
