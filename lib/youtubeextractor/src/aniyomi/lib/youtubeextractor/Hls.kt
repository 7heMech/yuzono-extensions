package aniyomi.lib.youtubeextractor

import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.math.abs

/**
 * Parses an HLS master playlist with codec, frame rate, dynamic range and estimated bandwidth labels.
 * Matches audio/subtitle groups to each variant, prefers default tracks, handles unordered media
 * attributes and resolves relative URIs. Variants are sorted by bandwidth so callers can retain the
 * best of otherwise identical streams.
 */
internal fun parseHlsVariants(
    playlistUrl: String,
    masterPlaylist: String,
    headers: Headers,
    videoNameGen: (String) -> String,
): List<Video> {
    if (PLAYLIST_SEPARATOR !in masterPlaylist) {
        return listOf(Video(videoUrl = playlistUrl, videoTitle = videoNameGen("Video"), headers = headers))
    }

    val mediaTracks = masterPlaylist.lineSequence()
        .filter { it.startsWith("#EXT-X-MEDIA:") }
        .map { it.hlsAttributes() }
        .toList()
    fun tracks(type: String, group: String?): List<Track> = mediaTracks
        .filter { it["TYPE"] == type && group != null && it["GROUP-ID"] == group }
        .sortedByDescending { it["DEFAULT"] == "YES" }
        .mapNotNull { attributes ->
            val uri = attributes["URI"] ?: return@mapNotNull null
            val url = playlistUrl.toHttpUrl().resolve(uri)?.toString() ?: return@mapNotNull null
            Track(url, attributes["NAME"] ?: attributes["LANGUAGE"] ?: type)
        }
        .distinctBy(Track::url)

    return masterPlaylist.substringAfter(PLAYLIST_SEPARATOR).split(PLAYLIST_SEPARATOR).mapNotNull { stream ->
        val attributes = stream.substringBefore('\n').hlsAttributes()
        val codec = attributes["CODECS"]
        if (!codec.isNullOrBlank() && codec.split(',').all { it.trim().substringBefore('.') in AUDIO_CODECS }) {
            return@mapNotNull null
        }

        val resolution = attributes["RESOLUTION"]?.let { resolution ->
            val standardQuality = QUALITY_REGEX.find(resolution)?.groupValues?.get(1)?.let(::standardQuality)
            if (!standardQuality.isNullOrBlank()) "$standardQuality ($resolution)" else resolution
        }
        val bandwidth = attributes["BANDWIDTH"]?.toLongOrNull()
        val bandwidthFormatted = bandwidth?.let { rate ->
            val averageRate = attributes["AVERAGE-BANDWIDTH"]?.toLongOrNull()?.takeIf { it > 0 } ?: rate
            averageRate.takeIf { it > 0 }?.let { "~%.2f Mbps".format(it / 1_000_000.0) }
        }
        val codecName = codec?.let(::formatCodecs)
        val frameRate = attributes["FRAME-RATE"]?.let { "$it fps" }
        val videoRange = attributes["VIDEO-RANGE"]?.takeUnless { it == "SDR" }
        val streamName = listOfNotNull(resolution, codecName, frameRate, videoRange).joinToString(" - ")
            .takeIf { it.isNotBlank() }
            ?: "Video"
        val streamLabel = if (bandwidthFormatted != null) "$streamName $bandwidthFormatted" else streamName

        val uri = stream.lineSequence().drop(1).map(String::trim).firstOrNull { it.isNotEmpty() && !it.startsWith('#') }
            ?: return@mapNotNull null
        val videoUrl = playlistUrl.toHttpUrl().resolve(uri)?.toString() ?: return@mapNotNull null

        bandwidth to Video(
            videoUrl = videoUrl,
            videoTitle = videoNameGen(streamLabel),
            headers = headers,
            subtitleTracks = tracks("SUBTITLES", attributes["SUBTITLES"]),
            audioTracks = tracks("AUDIO", attributes["AUDIO"]),
        )
    }
        .sortedByDescending { (bandwidth, _) -> bandwidth ?: 0L }
        .map { (_, video) -> video }
}

/** Converts comma-separated RFC 6381 codec identifiers into readable stream labels. */
internal fun formatCodecs(codecs: String): String = codecs.split(',').map { codec ->
    when (codec.trim().substringBefore('.')) {
        "avc1", "avc3" -> "H.264"
        "hev1", "hvc1" -> "HEVC"
        "vp09", "vp9" -> "VP9"
        "av01" -> "AV1"
        "dvhe", "dvh1", "dvav", "dva1" -> "Dolby Vision"
        "mp4a" -> "AAC"
        "opus" -> "Opus"
        "vorbis" -> "Vorbis"
        "ac-3" -> "AC-3"
        "ec-3" -> "E-AC-3"
        "flac" -> "FLAC"
        "alac" -> "ALAC"
        else -> codec.trim()
    }
}.distinct().joinToString(" + ")

private fun standardQuality(quality: String): String {
    val intQuality = quality.trim().toIntOrNull() ?: return quality
    val result = STANDARD_QUALITIES.minByOrNull { abs(it - intQuality) } ?: intQuality
    return "${result}p"
}

private fun String.hlsAttributes(): Map<String, String> = HLS_ATTRIBUTE_REGEX.findAll(this).associate {
    it.groupValues[1] to (it.groups[2]?.value ?: it.groupValues[3])
}

private const val PLAYLIST_SEPARATOR = "#EXT-X-STREAM-INF:"
private val HLS_ATTRIBUTE_REGEX = Regex("""([A-Z0-9-]+)=(?:"([^"]*)"|([^,\r\n]*))""")
private val QUALITY_REGEX = Regex("""[xX](\d+)""")
private val AUDIO_CODECS = setOf("mp4a", "opus", "vorbis", "ac-3", "ec-3", "flac", "alac")
private val STANDARD_QUALITIES = listOf(144, 240, 360, 480, 720, 1080, 1440, 2160)
