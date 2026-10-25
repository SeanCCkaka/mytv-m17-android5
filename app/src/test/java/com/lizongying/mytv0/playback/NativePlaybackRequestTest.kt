package com.lizongying.mytv0.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePlaybackRequestTest {
    private fun parse(url: String, headers: Map<String, String> = emptyMap(), type: String = "UNKNOWN", software: Boolean = true) =
        NativePlaybackRequest.parse(url, headers, type, software)

    @Test fun softwarePreservesCaseInsensitiveUserAgentAndReferer() {
        val request = parse("https://media.example/live.m3u8?token=private", mapOf("uSeR-aGeNt" to "TV/1.0", "REFERER" to "https://www.example/"))
        assertEquals("HLS", request.sourceType)
        assertEquals(listOf(":http-user-agent=TV/1.0", ":http-referrer=https://www.example/"), request.vlcOptions)
    }

    @Test fun softwareDoesNotSilentlyDropCookieOrAuthorization() {
        listOf("Cookie", "Authorization", "X-Playback-Token").forEach { header ->
            assertThrows(IllegalArgumentException::class.java) { parse("https://media.example/live.m3u8", mapOf(header to "private")) }
        }
    }

    @Test fun automaticHttpModePreservesCustomHeaders() {
        val headers = mapOf("User-Agent" to "TV/1.0", "Cookie" to "session=private", "X-Playback-Token" to "private")
        assertEquals(headers, parse("https://media.example/live.m3u8", headers, software = false).headers)
    }

    @Test fun softwareRejectsWebPagesAndUnclassifiedHttpEndpoints() {
        listOf("https://tv.example/", "https://tv.example/live.html", "https://tv.example/live/cctv1").forEach {
            assertThrows(IllegalArgumentException::class.java) { parse(it) }
        }
        assertThrows(IllegalArgumentException::class.java) { parse("https://tv.example/live.html", type = "HLS") }
    }

    @Test fun explicitFormatSupportsExtensionlessMedia() {
        assertEquals("HLS", parse("https://media.example/channel?id=1", type = "HLS").sourceType)
    }

    @Test fun localDiagnosticFileWorksInBothEngines() {
        listOf(true, false).forEach { software ->
            val request = parse("file:///data/user/0/example/files/compat-sample.mp4", software = software)
            assertEquals("file", request.scheme)
            assertEquals("PROGRESSIVE", request.sourceType)
            assertTrue(request.vlcOptions.isEmpty())
        }
    }

    @Test fun malformedOrDuplicateHeadersAreRejectedBeforePlayback() {
        listOf(mapOf("Referer" to "value\r\nInjected: true"), mapOf("Bad Header" to "x"), mapOf("User-Agent" to "a", "user-agent" to "b")).forEach {
            assertThrows(IllegalArgumentException::class.java) { parse("https://media.example/live.m3u8", it) }
        }
    }

    @Test fun nonHttpHeadersAreNeverSilentlyIgnored() {
        assertThrows(IllegalArgumentException::class.java) { parse("file:///tmp/test.mp4", mapOf("Referer" to "https://www.example/")) }
        assertThrows(IllegalArgumentException::class.java) { parse("rtsp://media.example/live", mapOf("Referer" to "https://www.example/"), software = false) }
        assertEquals("RTSP", parse("rtsp://media.example/live", mapOf("User-Agent" to "TV/1.0"), software = false).sourceType)
    }
}
