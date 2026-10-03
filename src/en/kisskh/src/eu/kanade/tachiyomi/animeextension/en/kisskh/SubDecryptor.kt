package eu.kanade.tachiyomi.animeextension.en.kisskh

import android.net.Uri
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.lib.cryptoaes.CryptoAES
import keiyoushi.utils.bodyString
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.io.File

class SubDecryptor(private val client: OkHttpClient, private val headers: Headers, private val baseurl: String) {
    suspend fun getSubtitles(subUrl: String, subLang: String): Track {
        val subHeaders = headers.newBuilder().apply {
            add("Accept", "application/json, text/plain, */*")
            add("Origin", baseurl)
            add("Referer", "$baseurl/")
        }.build()

        val subtitleData = client.newCall(
            GET(subUrl, subHeaders),
        ).awaitSuccess().bodyString()

        val chunks = subtitleData.split(CHUNK_REGEX)
            .filter(String::isNotBlank)
            .map(String::trim)

        val decrypted = chunks.mapIndexed { index, chunk ->
            val parts = chunk.split("\n")
            val text = parts.slice(1 until parts.size)
            val d = text.joinToString("\n") { decrypt(it) }

            listOf(index + 1, parts.first(), d).joinToString("\n")
        }.joinToString("\n\n")

        val file = File.createTempFile("subs", "srt")
            .also(File::deleteOnExit)

        file.writeText(decrypted)
        val uri = Uri.fromFile(file)

        return Track(uri.toString(), subLang)
    }

    companion object {
        private val CHUNK_REGEX by lazy { Regex("^\\d+$", RegexOption.MULTILINE) }

        private const val KEY = "AmSmZVcH93UQUezi"
        private const val KEY2 = "8056483646328763"

        private val IV = intArrayOf(1382367819, 1465333859, 1902406224, 1164854838)
        private val IV2 = intArrayOf(909653298, 909193779, 925905208, 892483379)
    }

    private val keyIvPairs by lazy {
        listOf(
            Pair(KEY.toByteArray(Charsets.UTF_8), IV.toByteArray()),
            Pair(KEY2.toByteArray(Charsets.UTF_8), IV2.toByteArray()),
        )
    }

    private fun decrypt(encryptedB64: String): String {
        if (encryptedB64.isBlank()) return ""
        return keyIvPairs.firstNotNullOfOrNull { (keyBytes, ivBytes) ->
            CryptoAES.decrypt(encryptedB64, keyBytes, ivBytes).takeIf(String::isNotEmpty)
        } ?: throw IllegalStateException("Failed to decrypt subtitle line")
    }

    private fun IntArray.toByteArray(): ByteArray = ByteArray(size * 4).also { bytes ->
        forEachIndexed { index, value ->
            bytes[index * 4] = (value shr 24).toByte()
            bytes[index * 4 + 1] = (value shr 16).toByte()
            bytes[index * 4 + 2] = (value shr 8).toByte()
            bytes[index * 4 + 3] = value.toByte()
        }
    }
}
