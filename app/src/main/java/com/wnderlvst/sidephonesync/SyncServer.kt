package com.wnderlvst.sidephonesync

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SyncServer(
    private val context: Context,
    port: Int = 8080,
    private val requestMediaDelete: ((List<Uri>) -> Unit)? = null
) : NanoHTTPD("0.0.0.0", port) {

    enum class Target(val value: String) {
        MUSIC("music"),
        PHOTOS("photos"),
        DOCUMENTS("documents"),
        CONTACTS("contacts"),
        OTHER("other");

        companion object {
            fun fromValue(value: String?): Target {
                return entries.firstOrNull { it.value == value } ?: OTHER
            }
        }
    }

    data class SavedUpload(
        val fileName: String,
        val target: Target,
        val locationLabel: String
    )

    data class LibraryItem(
        val id: String,
        val fileName: String,
        val title: String,
        val artist: String,
        val album: String,
        val mimeType: String,
        val size: Long,
        val modified: Long,
        val duration: Long,
        val albumId: Long,
        val target: Target,
        val source: String
    )

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.method == Method.GET && session.uri == "/" -> {
                    serveAsset("web/index.html", "text/html; charset=utf-8")
                }

                session.method == Method.GET && session.uri == "/app.css" -> {
                    serveAsset("web/app.css", "text/css; charset=utf-8")
                }

                session.method == Method.GET && session.uri == "/app.js" -> {
                    serveAsset("web/app.js", "application/javascript; charset=utf-8")
                }

                session.method == Method.POST && session.uri == "/upload" -> {
                    handleUpload(session)
                }

                session.method == Method.GET && session.uri == "/api/list" -> {
                    handleList(session)
                }

                session.method == Method.GET && session.uri == "/api/playlists" -> {
                    handlePlaylists()
                }

                session.method == Method.GET && session.uri == "/download" -> {
                    handleDownload(session)
                }

                session.method == Method.GET && session.uri == "/preview" -> {
                    handlePreview(session)
                }

                session.method == Method.GET && session.uri == "/art" -> {
                    handleAlbumArt(session)
                }

                session.method == Method.DELETE && session.uri == "/delete" -> {
                    handleDelete(session)
                }

                else -> {
                    newFixedLengthResponse(
                        Response.Status.NOT_FOUND,
                        "text/plain; charset=utf-8",
                        "Not found"
                    )
                }
            }
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain; charset=utf-8",
                "Error: ${e.message}"
            )
        }
    }

    private fun serveAsset(path: String, mimeType: String): Response {
        val input = context.assets.open(path)
        return newChunkedResponse(Response.Status.OK, mimeType, input)
    }

    private fun handleUpload(session: IHTTPSession): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)

        val tempPath = files["file"]
        val uploadedFileNames = session.parameters["file"]

        val originalName = if (
            uploadedFileNames != null &&
            uploadedFileNames.isNotEmpty() &&
            uploadedFileNames[0].isNotBlank()
        ) {
            uploadedFileNames[0]
        } else {
            "upload.bin"
        }

        val target = Target.fromValue(session.parameters["target"]?.firstOrNull())

        if (tempPath == null) {
            return jsonResponse(
                Response.Status.BAD_REQUEST,
                """{"ok":false,"message":"No file received."}"""
            )
        }

        val safeName = sanitizeFileName(originalName)
        val saved = saveUpload(File(tempPath), safeName, target)

        return jsonResponse(
            Response.Status.OK,
            """
            {
              "ok": true,
              "fileName": ${jsonString(saved.fileName)},
              "target": ${jsonString(saved.target.value)},
              "location": ${jsonString(saved.locationLabel)}
            }
            """.trimIndent()
        )
    }

    private fun handleList(session: IHTTPSession): Response {
        val target = Target.fromValue(session.parameters["target"]?.firstOrNull())

        val items = when (target) {
            Target.MUSIC -> listMediaStoreAudio()
            Target.PHOTOS -> listMediaStoreImages()
            Target.DOCUMENTS -> listDownloadsDocuments()
            Target.CONTACTS -> listAppFolder("Contacts", Target.CONTACTS)
            Target.OTHER -> listAppFolder("Uploads", Target.OTHER)
        }

        val json = buildString {
            append("""{"ok":true,"items":[""")

            items.forEachIndexed { index, item ->
                if (index > 0) append(",")

                append(
                    """
                    {
                        "id": ${jsonString(item.id)},
                        "fileName": ${jsonString(item.fileName)},
                        "title": ${jsonString(item.title)},
                        "artist": ${jsonString(item.artist)},
                        "album": ${jsonString(item.album)},
                        "mimeType": ${jsonString(item.mimeType)},
                        "size": ${item.size},
                        "modified": ${item.modified},
                        "duration": ${item.duration},
                        "albumId": ${item.albumId},
                        "target": ${jsonString(item.target.value)},
                        "source": ${jsonString(item.source)}
                    }
                    """.trimIndent()
                )
            }

            append("]}")
        }

        return jsonResponse(Response.Status.OK, json)
    }

    private fun handlePlaylists(): Response {
        return jsonResponse(Response.Status.OK, """{"ok":true,"items":[]}""")
    }

    private fun handleDownload(session: IHTTPSession): Response {
        val target = Target.fromValue(session.parameters["target"]?.firstOrNull())

        val id = session.parameters["id"]?.firstOrNull()
            ?: return jsonResponse(
                Response.Status.BAD_REQUEST,
                """{"ok":false,"message":"Missing id"}"""
            )

        return when (target) {
            Target.MUSIC -> downloadMediaStoreItem(
                id = id,
                baseUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                target = Target.MUSIC,
                attachment = true
            )

            Target.PHOTOS -> downloadMediaStoreItem(
                id = id,
                baseUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                target = Target.PHOTOS,
                attachment = true
            )

            Target.DOCUMENTS -> downloadMediaStoreItem(
                id = id,
                baseUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                target = Target.DOCUMENTS,
                attachment = true
            )

            Target.CONTACTS -> downloadAppFolderItem("Contacts", id)
            Target.OTHER -> downloadAppFolderItem("Uploads", id)
        }
    }

    private fun handlePreview(session: IHTTPSession): Response {
        val target = Target.fromValue(session.parameters["target"]?.firstOrNull())

        val id = session.parameters["id"]?.firstOrNull()
            ?: return jsonResponse(
                Response.Status.BAD_REQUEST,
                """{"ok":false,"message":"Missing id"}"""
            )

        return when (target) {
            Target.PHOTOS -> downloadMediaStoreItem(
                id = id,
                baseUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                target = Target.PHOTOS,
                attachment = false
            )

            else -> {
                jsonResponse(
                    Response.Status.BAD_REQUEST,
                    """{"ok":false,"message":"Preview unavailable"}"""
                )
            }
        }
    }

    private fun handleAlbumArt(session: IHTTPSession): Response {
        val id = session.parameters["id"]?.firstOrNull()

        if (!id.isNullOrBlank()) {
            val response = getEmbeddedAlbumArt(id)
            if (response != null) return response
        }

        val albumId = session.parameters["albumId"]?.firstOrNull()?.toLongOrNull()

        if (albumId != null && albumId > 0L) {
            val uri = Uri.parse("content://media/external/audio/albumart/$albumId")
            val input = context.contentResolver.openInputStream(uri)

            if (input != null) {
                return newChunkedResponse(Response.Status.OK, "image/jpeg", input)
            }
        }

        return newFixedLengthResponse(
            Response.Status.NOT_FOUND,
            "text/plain; charset=utf-8",
            "No cover"
        )
    }

    private fun getEmbeddedAlbumArt(id: String): Response? {
        val numericId = id.toLongOrNull() ?: return null
        val uri = ContentUris.withAppendedId(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            numericId
        )

        val retriever = MediaMetadataRetriever()

        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                retriever.setDataSource(descriptor.fileDescriptor)

                val artBytes = retriever.embeddedPicture ?: return null

                newFixedLengthResponse(
                    Response.Status.OK,
                    "image/jpeg",
                    artBytes.inputStream(),
                    artBytes.size.toLong()
                )
            }
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun handleDelete(session: IHTTPSession): Response {
        val target = Target.fromValue(session.parameters["target"]?.firstOrNull())

        val id = session.parameters["id"]?.firstOrNull()
            ?: return jsonResponse(
                Response.Status.BAD_REQUEST,
                """{"ok":false,"message":"Missing id"}"""
            )

        return when (target) {
            Target.MUSIC -> deleteMediaStoreItem(
                id = id,
                baseUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            )

            Target.PHOTOS -> deleteMediaStoreItem(
                id = id,
                baseUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            )

            Target.DOCUMENTS -> deleteMediaStoreItem(
                id = id,
                baseUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            )

            Target.CONTACTS -> deleteAppFolderItem("Contacts", id)
            Target.OTHER -> deleteAppFolderItem("Uploads", id)
        }
    }

    private fun saveUpload(tempFile: File, safeName: String, target: Target): SavedUpload {
        return when (target) {
            Target.MUSIC -> saveToMusicLibrary(tempFile, safeName)
            Target.PHOTOS -> saveToPhotoLibrary(tempFile, safeName)
            Target.DOCUMENTS -> saveToDownloads(tempFile, safeName)
            Target.CONTACTS -> saveToAppFolder(tempFile, safeName, "Contacts", target)
            Target.OTHER -> saveToAppFolder(tempFile, safeName, "Uploads", target)
        }
    }

    private fun saveToMusicLibrary(tempFile: File, safeName: String): SavedUpload {
        val resolver = context.contentResolver
        val mimeType = guessMimeType(safeName)

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, safeName)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Sidephone")
            put(MediaStore.Audio.Media.IS_MUSIC, 1)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create music file.")

        resolver.openOutputStream(uri)?.use { output ->
            FileInputStream(tempFile).use { input ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Could not write music file.")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }

        return SavedUpload(safeName, Target.MUSIC, "Music/Sidephone")
    }

    private fun saveToPhotoLibrary(tempFile: File, safeName: String): SavedUpload {
        val resolver = context.contentResolver
        val mimeType = guessMimeType(safeName)

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, safeName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Sidephone")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create photo.")

        resolver.openOutputStream(uri)?.use { output ->
            FileInputStream(tempFile).use { input ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Could not write photo.")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }

        return SavedUpload(safeName, Target.PHOTOS, "Pictures/Sidephone")
    }

    private fun saveToDownloads(tempFile: File, safeName: String): SavedUpload {
        val resolver = context.contentResolver
        val mimeType = guessMimeType(safeName)

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, safeName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Sidephone/Documents")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create document.")

        resolver.openOutputStream(uri)?.use { output ->
            FileInputStream(tempFile).use { input ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Could not write document.")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }

        return SavedUpload(safeName, Target.DOCUMENTS, "Download/Sidephone/Documents")
    }

    private fun saveToAppFolder(
        tempFile: File,
        safeName: String,
        folderName: String,
        target: Target
    ): SavedUpload {
        val dir = File(context.getExternalFilesDir(null), folderName).also {
            if (!it.exists()) it.mkdirs()
        }

        val targetFile = uniqueFile(File(dir, safeName))

        FileInputStream(tempFile).use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        return SavedUpload(
            fileName = targetFile.name,
            target = target,
            locationLabel = "Android/data/${context.packageName}/files/$folderName"
        )
    }

    private fun listMediaStoreAudio(): List<LibraryItem> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID
        )

        val items = mutableListOf<LibraryItem>()

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol) ?: "unknown"

                items.add(
                    LibraryItem(
                        id = cursor.getLong(idCol).toString(),
                        fileName = name,
                        title = cursor.getString(titleCol) ?: name,
                        artist = cursor.getString(artistCol) ?: "",
                        album = cursor.getString(albumCol) ?: "",
                        mimeType = cursor.getString(mimeCol) ?: guessMimeType(name),
                        size = cursor.getLong(sizeCol),
                        modified = cursor.getLong(modifiedCol),
                        duration = cursor.getLong(durationCol),
                        albumId = cursor.getLong(albumIdCol),
                        target = Target.MUSIC,
                        source = "Music"
                    )
                )
            }
        }

        return items
    }

    private fun listMediaStoreImages(): List<LibraryItem> {
        return queryMediaStore(
            target = Target.PHOTOS,
            baseUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_MODIFIED
            ),
            source = "Photos"
        )
    }

    private fun listDownloadsDocuments(): List<LibraryItem> {
        return queryMediaStore(
            target = Target.DOCUMENTS,
            baseUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.MIME_TYPE,
                MediaStore.Downloads.SIZE,
                MediaStore.Downloads.DATE_MODIFIED
            ),
            source = "Documents"
        )
    }

    private fun queryMediaStore(
        target: Target,
        baseUri: Uri,
        projection: Array<String>,
        source: String
    ): List<LibraryItem> {
        val items = mutableListOf<LibraryItem>()

        context.contentResolver.query(
            baseUri,
            projection,
            null,
            null,
            "${projection[4]} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(projection[0])
            val nameCol = cursor.getColumnIndexOrThrow(projection[1])
            val mimeCol = cursor.getColumnIndexOrThrow(projection[2])
            val sizeCol = cursor.getColumnIndexOrThrow(projection[3])
            val modifiedCol = cursor.getColumnIndexOrThrow(projection[4])

            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol) ?: "unknown"

                items.add(
                    LibraryItem(
                        id = cursor.getLong(idCol).toString(),
                        fileName = name,
                        title = name,
                        artist = "",
                        album = "",
                        mimeType = cursor.getString(mimeCol) ?: guessMimeType(name),
                        size = cursor.getLong(sizeCol),
                        modified = cursor.getLong(modifiedCol),
                        duration = 0L,
                        albumId = 0L,
                        target = target,
                        source = source
                    )
                )
            }
        }

        return items
    }

    private fun listAppFolder(folderName: String, target: Target): List<LibraryItem> {
        val dir = File(context.getExternalFilesDir(null), folderName)
        if (!dir.exists()) return emptyList()

        return dir.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.map {
                LibraryItem(
                    id = it.name,
                    fileName = it.name,
                    title = it.name,
                    artist = "",
                    album = "",
                    mimeType = guessMimeType(it.name),
                    size = it.length(),
                    modified = it.lastModified() / 1000,
                    duration = 0L,
                    albumId = 0L,
                    target = target,
                    source = folderName
                )
            }
            ?: emptyList()
    }

    private fun downloadMediaStoreItem(
        id: String,
        baseUri: Uri,
        target: Target,
        attachment: Boolean
    ): Response {
        val numericId = id.toLongOrNull()
            ?: return jsonResponse(
                Response.Status.BAD_REQUEST,
                """{"ok":false,"message":"Invalid id"}"""
            )

        val uri = ContentUris.withAppendedId(baseUri, numericId)

        val fileName = findDisplayName(uri, target) ?: "download.bin"
        val mimeType = guessMimeType(fileName)

        val input = context.contentResolver.openInputStream(uri)
            ?: return jsonResponse(
                Response.Status.NOT_FOUND,
                """{"ok":false,"message":"File not found"}"""
            )

        val response = newChunkedResponse(Response.Status.OK, mimeType, input)

        if (attachment) {
            response.addHeader(
                "Content-Disposition",
                "attachment; filename=\"${fileName.replace("\"", "_")}\""
            )
        }

        return response
    }

    private fun findDisplayName(uri: Uri, target: Target): String? {
        val projection = when (target) {
            Target.MUSIC -> arrayOf(MediaStore.Audio.Media.DISPLAY_NAME)
            Target.PHOTOS -> arrayOf(MediaStore.Images.Media.DISPLAY_NAME)
            Target.DOCUMENTS -> arrayOf(MediaStore.Downloads.DISPLAY_NAME)
            else -> return null
        }

        return context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun downloadAppFolderItem(folderName: String, id: String): Response {
        val safeName = sanitizeFileName(id)
        val file = File(context.getExternalFilesDir(null), folderName).resolve(safeName)

        if (!file.exists() || !file.isFile) {
            return jsonResponse(
                Response.Status.NOT_FOUND,
                """{"ok":false,"message":"File not found"}"""
            )
        }

        val response = newFixedLengthResponse(
            Response.Status.OK,
            guessMimeType(file.name),
            file.inputStream(),
            file.length()
        )

        response.addHeader(
            "Content-Disposition",
            "attachment; filename=\"${file.name.replace("\"", "_")}\""
        )

        return response
    }

    private fun deleteMediaStoreItem(id: String, baseUri: Uri): Response {
        val numericId = id.toLongOrNull()
            ?: return jsonResponse(
                Response.Status.BAD_REQUEST,
                """{"ok":false,"message":"Invalid id"}"""
            )

        val uri = ContentUris.withAppendedId(baseUri, numericId)

        return try {
            val deletedRows = context.contentResolver.delete(uri, null, null)

            if (deletedRows > 0) {
                jsonResponse(Response.Status.OK, """{"ok":true,"message":"Deleted."}""")
            } else {
                requestDeleteOnDevice(uri)
            }
        } catch (_: SecurityException) {
            requestDeleteOnDevice(uri)
        } catch (e: Exception) {
            jsonResponse(
                Response.Status.INTERNAL_ERROR,
                """{"ok":false,"message":${jsonString(e.message ?: "Unknown error")}}"""
            )
        }
    }

    private fun requestDeleteOnDevice(uri: Uri): Response {
        val callback = requestMediaDelete

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && callback != null) {
            callback(listOf(uri))

            jsonResponse(
                Response.Status.OK,
                """{"ok":true,"pending":true,"message":"Confirm delete on Sidephone."}"""
            )
        } else {
            jsonResponse(
                Response.Status.FORBIDDEN,
                """{"ok":false,"message":"Delete not allowed by Android."}"""
            )
        }
    }

    private fun deleteAppFolderItem(folderName: String, id: String): Response {
        val safeName = sanitizeFileName(id)
        val file = File(context.getExternalFilesDir(null), folderName).resolve(safeName)

        if (!file.exists() || !file.isFile) {
            return jsonResponse(
                Response.Status.NOT_FOUND,
                """{"ok":false,"message":"File not found."}"""
            )
        }

        return if (file.delete()) {
            jsonResponse(Response.Status.OK, """{"ok":true,"message":"Deleted."}""")
        } else {
            jsonResponse(
                Response.Status.INTERNAL_ERROR,
                """{"ok":false,"message":"Could not delete file."}"""
            )
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name
            .substringAfterLast("/")
            .substringAfterLast("\\")
            .replace(Regex("[^a-zA-Z0-9._ ()\\-äöüÄÖÜß]"), "_")
            .ifBlank { "upload.bin" }
    }

    private fun uniqueFile(file: File): File {
        if (!file.exists()) return file

        val parent = file.parentFile ?: return file

        val name = file.nameWithoutExtension
        val ext = file.extension
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

        return if (ext.isBlank()) {
            File(parent, "$name-$stamp")
        } else {
            File(parent, "$name-$stamp.$ext")
        }
    }

    private fun guessMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.US)

        return MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(extension)
            ?: when (extension) {
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "flac" -> "audio/flac"
                "wav" -> "audio/wav"
                "ogg" -> "audio/ogg"
                "aac" -> "audio/aac"
                "opus" -> "audio/opus"

                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "heic" -> "image/heic"

                "pdf" -> "application/pdf"
                "txt" -> "text/plain"
                "md" -> "text/markdown"
                "vcf" -> "text/vcard"
                "epub" -> "application/epub+zip"
                "zip" -> "application/zip"

                else -> "application/octet-stream"
            }
    }

    private fun jsonString(value: String): String {
        return "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r") + "\""
    }

    private fun jsonResponse(status: Response.Status, body: String): Response {
        return newFixedLengthResponse(
            status,
            "application/json; charset=utf-8",
            body
        )
    }

    companion object {
        fun getLocalIpAddress(): String? {
            return NetworkInterface.getNetworkInterfaces()
                .toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { address ->
                    !address.isLoopbackAddress &&
                            address.hostAddress != null &&
                            address.hostAddress.indexOf(':') < 0
                }
                ?.hostAddress
        }
    }
}