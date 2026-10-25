package com.lizongying.mytv0.playback

import com.google.gson.JsonParser
import java.net.URI
import java.security.MessageDigest

/** The one supported public web entry. Remote JavaScript is read as text, never executed. */
object CztvWebSource {
    const val ENTRY_URL = "https://www.cztv.com/liveTV/101"
    const val PLAY_INFO_URL = "https://zlive-das.cztv.com/zapp/live/tv/channel/playInfo?channelId=101&platform=WEB"
    const val USER_AGENT = "Mozilla/5.0"
    private const val MEDIA_PATH = "/live/channel01720Pnew.m3u8"
    private val mediaHosts = (1..7).map { "zwebl0$it.cztv.com" }.toSet()
    val playbackHeaders: Map<String, String> get() = mapOf("User-Agent" to USER_AGENT, "Referer" to ENTRY_URL)

    fun recognizes(url: String) = url == ENTRY_URL

    fun validateEntryHeaders(headers: Map<String, String>?) {
        require(headers.orEmpty().keys.all { it.equals("User-Agent", true) || it.equals("Referer", true) }) {
            "官方网页入口不支持额外请求头，请检查此频道的配置。"
        }
    }

    fun appScript(html: String): String {
        val scripts = Regex("<script\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html)
        val candidates = scripts.mapNotNull { script ->
            Regex("\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                .find(script.value)?.groupValues?.get(1)
        }.mapNotNull { value ->
            try {
                val uri = URI(ENTRY_URL).resolve(value)
                if (isWebAsset(uri) && Regex("/assets/index-[A-Za-z0-9_-]+\\.js").matches(uri.path)) uri.toString() else null
            } catch (_: Exception) { null }
        }.distinct().toList()
        require(candidates.size == 1) { "Official app module changed" }
        return candidates.single()
    }

    fun liveScript(appJavaScript: String): String {
        val files = Regex("[\"'](?:\\./|/)?(?:assets/)?(directSeedingTV-[A-Za-z0-9_-]+\\.js)[\"']")
            .findAll(appJavaScript).map { it.groupValues[1] }.distinct().toList()
        require(files.size == 1) { "Official live module changed" }
        return "https://www.cztv.com/assets/${files.single()}"
    }

    fun publicAuthParameters(liveJavaScript: String): Map<String, String> {
        val start = Regex("(?:[\"']cdnAuthParams[\"']|cdnAuthParams)\\s*:\\s*\\{").find(liveJavaScript)
            ?: throw IllegalArgumentException("Official public playback parameters missing")
        val open = start.range.last
        var depth = 0
        var quote: Char? = null
        var escaped = false
        var end = -1
        for (index in open until minOf(liveJavaScript.length, open + 32_768)) {
            val c = liveJavaScript[index]
            if (quote != null) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == quote) quote = null
            } else when (c) {
                '\'', '"' -> quote = c
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) { end = index; break } }
            }
        }
        require(end > open) { "Official public playback parameters malformed" }
        val body = liveJavaScript.substring(open + 1, end)
        val parameters = linkedMapOf<String, String>()
        Regex("[\"'](zwebl0[1-7]\\.cztv\\.com)[\"']\\s*:\\s*\\{\\s*key\\s*:\\s*[\"']([A-Za-z0-9_-]{8,128})[\"']\\s*}")
            .findAll(body).forEach {
                val host = it.groupValues[1]
                require(host !in parameters) { "Duplicate official playback host" }
                parameters[host] = it.groupValues[2]
            }
        require(parameters.isNotEmpty()) { "Official public playback parameters unsupported" }
        return parameters
    }

    fun public720pUrls(json: String): List<String> {
        val root = JsonParser.parseString(json).asJsonObject
        require(root["success"]?.asJsonPrimitive?.isBoolean == true && root["success"].asBoolean && root["code"]?.asInt == 200)
        val data = root["data"].asJsonObject
        require(data["channelId"]?.asString == "101" && data["channelName"]?.asString == "浙江卫视")
        val streams = data["multiBitrateStreamList"].asJsonArray.map { it.asJsonObject }
            .filter { it["bitrateCode"]?.asString == "720P" }
        require(streams.size == 1) { "Official 720P stream missing" }
        val stream = streams.single()
        require(stream["vipIcon"]?.asString == "") { "Only the public web stream is supported" }
        require(stream["isHdr"] == null || !stream["isHdr"].asBoolean)
        val urls = stream["urlList"].asJsonArray.map { it.asString }.distinct()
        require(urls.isNotEmpty() && urls.size <= 10)
        urls.forEach(::validateUnsignedMediaUrl)
        return urls
    }

    fun signed720pUrl(url: String, parameters: Map<String, String>, nowMillis: Long): String {
        val uri = validateUnsignedMediaUrl(url)
        require(nowMillis > 0)
        val publicParameter = parameters[uri.host]
            ?: throw IllegalArgumentException("Official CDN parameters unavailable")
        // Reproduce this public page's Date.now()/UTF-8 MD5 flow, without persisting its result.
        val material = "${uri.rawPath}-$nowMillis-0-0-$publicParameter"
        val digest = MessageDigest.getInstance("MD5").digest(material.toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        val hash = buildString(32) { digest.forEach { append(hex[(it.toInt() and 255) ushr 4]); append(hex[it.toInt() and 15]) } }
        return "$url?auth_key=$nowMillis-0-0-$hash"
    }

    fun validatePlain720pPlaylist(playlist: String, mediaUrl: String) {
        val base = URI(mediaUrl)
        require(isMediaHost(base) && base.path == MEDIA_PATH)
        val lines = playlist.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        require(lines.firstOrNull() == "#EXTM3U") { "Official stream is not HLS" }
        require(lines.none { it.startsWith("#EXT-X-KEY") || it.startsWith("#EXT-X-SESSION-KEY") ||
            it.startsWith("#EXT-X-STREAM-INF") || it.startsWith("#EXT-X-MAP") }) { "Only the public unencrypted 720P stream is supported" }
        val segments = lines.filter { !it.startsWith('#') }
        require(segments.isNotEmpty() && lines.any { it.startsWith("#EXTINF:") })
        segments.forEach {
            val uri = base.resolve(it)
            require(isMediaHost(uri) && uri.path.startsWith("/live/channel01720Pnew/") && uri.path.endsWith(".ts") &&
                uri.rawQuery == null && uri.rawFragment == null) { "Official segment location changed" }
        }
    }

    fun permitsFetch(url: String): Boolean = try {
        val uri = URI(url)
        url == ENTRY_URL || url == PLAY_INFO_URL ||
            (isWebAsset(uri) && Regex("/assets/(?:index|directSeedingTV)-[A-Za-z0-9_-]+\\.js").matches(uri.path)) ||
            (isMediaHost(uri) && uri.path == MEDIA_PATH && uri.rawFragment == null &&
                Regex("auth_key=[0-9]+-0-0-[0-9a-f]{32}").matches(uri.rawQuery ?: ""))
    } catch (_: Exception) { false }

    private fun validateUnsignedMediaUrl(url: String): URI = URI(url).also {
        require(isMediaHost(it) && it.rawPath == MEDIA_PATH && it.rawQuery == null && it.rawFragment == null) {
            "Official 720P URL changed"
        }
    }

    private fun isMediaHost(uri: URI) = uri.scheme == "https" && uri.host in mediaHosts &&
        uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443)

    private fun isWebAsset(uri: URI) = uri.scheme == "https" && uri.host == "www.cztv.com" &&
        uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443) && uri.rawQuery == null && uri.rawFragment == null
}
