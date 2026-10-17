package eu.kanade.tachiyomi.animeextension.en.moviesmod

import eu.kanade.tachiyomi.network.GET
import keiyoushi.network.get
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.OkHttpClient
import okio.BufferedSource
import org.nanohttpd.protocols.http.IHTTPSession
import org.nanohttpd.protocols.http.NanoHTTPD
import org.nanohttpd.protocols.http.response.Response
import org.nanohttpd.protocols.http.response.Response.newFixedLengthResponse
import org.nanohttpd.protocols.http.response.Status
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Serves the video inside a Resume Cloud zip to the player as a plain, seekable file over loopback.
 *
 * Instant links end at Google's UploadServer, which ignores Range, so the player can't seek in them.
 * The Resume Cloud worker honours Range but wraps the same file in a zip. That zip deflates the file
 * into stored (uncompressed) blocks of a fixed size, so every byte of the video sits at a known offset
 * of the zip, and a range of the video is served by dropping the 5-byte header in front of each block.
 */
object ZipVideoServer : NanoHTTPD("127.0.0.1", 0) {

    private const val BLOCK_SIZE = 32768
    private const val BLOCK_HEADER_SIZE = 5
    private const val PROBE_SIZE = 4096L

    private class Entry(
        val client: OkHttpClient,
        val headers: Headers,
        val zipUrl: String,
        val dataStart: Long,
        val size: Long,
        val mime: String,
    )

    // Only registered zips are served, so the port can't be used to fetch arbitrary URLs.
    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 32
    }

    /**
     * Checks the layout of the zip and returns the loopback URL of the file inside it, or `null`
     * if the zip isn't made of fixed-size stored blocks.
     */
    suspend fun register(client: OkHttpClient, headers: Headers, zipUrl: String): String? {
        val head = client.fetchRange(headers, zipUrl, "0-${PROBE_SIZE - 1}")
        val total = head.total ?: return null
        val local = head.bytes.littleEndian()
        if (local.int(0) != 0x04034b50 || local.short(8) != 8) return null
        val nameLength = local.short(26)
        val dataStart = 30L + nameLength + local.short(28)
        if (dataStart + BLOCK_HEADER_SIZE > head.bytes.size) return null
        val name = String(head.bytes, 30, nameLength)

        val tail = client.fetchRange(headers, zipUrl, "-${minOf(PROBE_SIZE, total)}").bytes
        val (compressed, size) = centralDirectorySizes(tail) ?: return null
        // Stored blocks only: each full block adds its header, plus at most one empty final block.
        val blocks = (size + BLOCK_SIZE - 1) / BLOCK_SIZE
        if (compressed - size != blocks * BLOCK_HEADER_SIZE && compressed - size != (blocks + 1) * BLOCK_HEADER_SIZE) return null
        val firstStored = (head.bytes[dataStart.toInt()].toInt() and 0x06) == 0
        if (!firstStored || (size > BLOCK_SIZE && local.short(dataStart.toInt() + 1) != BLOCK_SIZE)) return null

        val mime = when (name.substringAfterLast('.').lowercase()) {
            "mkv" -> "video/x-matroska"
            "mp4" -> "video/mp4"
            else -> "application/octet-stream"
        }
        val id = UUID.nameUUIDFromBytes(zipUrl.toByteArray()).toString()
        synchronized(this) {
            if (!isAlive) start()
            entries[id] = Entry(client, headers, zipUrl, dataStart, size, mime)
        }
        return "http://127.0.0.1:$listeningPort/$id"
    }

    override fun handle(session: IHTTPSession): Response {
        val entry = synchronized(this) { entries[session.uri.trim('/')] }
            ?: return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        return try {
            serve(entry, session.headers["range"])
        } catch (e: Exception) {
            newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.toString())
        }
    }

    private fun serve(entry: Entry, rangeHeader: String?): Response {
        val range = rangeHeader?.trim()?.let { RANGE_REGEX.matchEntire(it) }
        var start = 0L
        var end = entry.size - 1
        if (range != null) {
            val (from, to) = range.destructured
            if (from.isEmpty()) {
                start = (entry.size - (to.toLongOrNull() ?: 0)).coerceAtLeast(0)
            } else {
                start = from.toLongOrNull() ?: Long.MAX_VALUE
                end = to.toLongOrNull()?.coerceAtMost(end) ?: end
            }
            if (start > end) {
                return newFixedLengthResponse(Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "")
                    .apply { addHeader("Content-Range", "bytes */${entry.size}") }
            }
        }

        val zipRange = "bytes=${entry.zipOffset(start)}-${entry.zipOffset(end)}"
        val response = entry.client.newCall(GET(entry.zipUrl, entry.headers.newBuilder().set("Range", zipRange).build())).execute()
        if (response.code != 206) {
            response.close()
            return newFixedLengthResponse(Status.lookup(response.code) ?: Status.INTERNAL_ERROR, MIME_PLAINTEXT, "HTTP ${response.code}")
        }

        val length = end - start + 1
        val data = StoredBlockInputStream(response.body.source(), BLOCK_SIZE - (start % BLOCK_SIZE).toInt(), length)
        val status = if (range == null) Status.OK else Status.PARTIAL_CONTENT
        return newFixedLengthResponse(status, entry.mime, data, length).apply {
            addHeader("Accept-Ranges", "bytes")
            if (range != null) addHeader("Content-Range", "bytes $start-$end/${entry.size}")
        }
    }

    private fun Entry.zipOffset(position: Long): Long = dataStart + (position / BLOCK_SIZE) * (BLOCK_SIZE + BLOCK_HEADER_SIZE) + BLOCK_HEADER_SIZE + position % BLOCK_SIZE

    /** Reads the compressed and uncompressed size of the first entry of the central directory. */
    private fun centralDirectorySizes(tail: ByteArray): Pair<Long, Long>? {
        val buffer = tail.littleEndian()
        val offset = (0..tail.size - 46).firstOrNull { buffer.int(it) == 0x02014b50 } ?: return null
        var compressed = buffer.int(offset + 20).toLong() and 0xFFFFFFFFL
        var size = buffer.int(offset + 24).toLong() and 0xFFFFFFFFL
        val extraStart = offset + 46 + buffer.short(offset + 28)
        val extraEnd = (extraStart + buffer.short(offset + 30)).coerceAtMost(tail.size)

        // ZIP64 stores the sizes that don't fit 32 bits in its extra field, uncompressed first.
        var field = extraStart
        while (field + 4 <= extraEnd) {
            val fieldSize = buffer.short(field + 2)
            if (buffer.short(field) == 0x0001) {
                var value = field + 4
                if (size == 0xFFFFFFFFL && value + 8 <= extraEnd) size = buffer.getLong(value).also { value += 8 }
                if (compressed == 0xFFFFFFFFL && value + 8 <= extraEnd) compressed = buffer.getLong(value)
            }
            field += 4 + fieldSize
        }
        return if (size == 0xFFFFFFFFL || compressed == 0xFFFFFFFFL) null else compressed to size
    }

    private class Fetched(val bytes: ByteArray, val total: Long?)

    private suspend fun OkHttpClient.fetchRange(headers: Headers, url: String, range: String): Fetched {
        val rangeHeaders = headers.newBuilder().set("Range", "bytes=$range").build()
        return get(url, rangeHeaders, CacheControl.FORCE_NETWORK).use { response ->
            check(response.code == 206) { "Range not supported (HTTP ${response.code})" }
            val total = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
            Fetched(response.body.bytes(), total)
        }
    }

    private fun ByteArray.littleEndian(): ByteBuffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)

    private fun ByteBuffer.int(index: Int): Int = getInt(index)

    private fun ByteBuffer.short(index: Int): Int = getShort(index).toInt() and 0xFFFF

    private val RANGE_REGEX = Regex("""bytes=(\d*)-(\d*)""")

    /** Reads [length] bytes of stored deflate data, skipping the block header in front of every block. */
    private class StoredBlockInputStream(
        private val source: BufferedSource,
        private var blockLeft: Int,
        private var left: Long,
    ) : InputStream() {
        private val single = ByteArray(1)

        override fun read(): Int = if (read(single, 0, 1) == -1) -1 else single[0].toInt() and 0xFF

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (left <= 0) return -1
            if (blockLeft == 0) {
                source.skip(BLOCK_HEADER_SIZE.toLong())
                blockLeft = BLOCK_SIZE
            }
            val read = source.read(b, off, minOf(len.toLong(), blockLeft.toLong(), left).toInt())
            if (read == -1) return -1
            blockLeft -= read
            left -= read
            return read
        }

        override fun close() = source.close()
    }
}
