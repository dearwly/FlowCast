package com.dlna.dlnacaster

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream

class MediaServer(private val context: Context, port: Int = 8080) : NanoHTTPD(port) {

    companion object {
        const val URI_PATH = "media"
        private const val TAG = "MediaServer"
    }
    private var mediaUri: Uri? = null

    fun setMediaUri(uri: Uri?) {
        this.mediaUri = uri
    }

    override fun serve(session: IHTTPSession): Response {
        val currentUri = mediaUri
        if (session.uri != "/$URI_PATH" || currentUri == null) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }

        try {
            val fileSize = getFileSize(currentUri)
            val mimeType = context.contentResolver.getType(currentUri) ?: "application/octet-stream"

            val rangeHeader = session.headers["range"]
            return if (rangeHeader != null && fileSize > 0 && rangeHeader.startsWith("bytes=")) {
                // range request
                val rangeValue = rangeHeader.substring("bytes=".length)
                val parts = rangeValue.split("-")
                val start = parts.getOrNull(0)?.toLongOrNull() ?: 0
                var end = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1].toLong() else fileSize - 1

                if (start >= fileSize) {
                    newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "").apply {
                        addHeader("Content-Range", "bytes */$fileSize")
                    }
                } else {
                    if (end >= fileSize) end = fileSize - 1
                    val chunkLength = end - start + 1

                    val inputStream: InputStream = context.contentResolver.openInputStream(currentUri)!!
                    inputStream.skip(start)

                    val response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mimeType, inputStream, chunkLength)
                    response.addHeader("Content-Length", chunkLength.toString())
                    response.addHeader("Content-Range", "bytes $start-$end/$fileSize")
                    response.addHeader("Accept-Ranges", "bytes")
                    Log.d(TAG, "Serving partial content: bytes $start-$end/$fileSize")
                    return response
                }
            } else {
                //file request
                val inputStream: InputStream = context.contentResolver.openInputStream(currentUri)!!
                val response = newFixedLengthResponse(Response.Status.OK, mimeType, inputStream, fileSize)
                response.addHeader("Content-Length", fileSize.toString())
                response.addHeader("Accept-Ranges", "bytes")
                Log.d(TAG, "Serving full content, size: $fileSize")
                return response
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error serving file", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Internal Server Error.")
        }
    }

    private fun getFileSize(uri: Uri): Long {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex != -1 && cursor.moveToFirst()) {
                try {
                    return cursor.getLong(sizeIndex)
                } catch (e: Exception) {
                    Log.e(TAG, "Could not get file size, maybe column type is not LONG.", e)
                }
            }
        }
        return 0
    }
}