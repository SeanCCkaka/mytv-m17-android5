package com.lizongying.mytv0.playback

import org.junit.Assert.*
import org.junit.Test

class CztvWebSourceTest {
    private val base = "https://zwebl06.cztv.com/live/channel01720Pnew.m3u8"
    private val live = """const settings={CZTVPlugin:{cdnAuthParams:{"zwebl06.cztv.com":{key:"public-test-key"}}}};"""
    private fun info(url: String = base, id: String = "101", quality: String = "720P", vip: String = "") =
        """{"success":true,"code":200,"data":{"channelId":"$id","channelName":"浙江卫视","multiBitrateStreamList":[{"bitrateCode":"$quality","vipIcon":"$vip","isHdr":false,"urlList":["$url"]}]}}"""

    @Test fun onlyTheExactZhejiangEntryIsRecognized() {
        assertTrue(CztvWebSource.recognizes(CztvWebSource.ENTRY_URL))
        listOf("http://www.cztv.com/liveTV/101", "https://www.cztv.com/liveTV/102", "https://www.cztv.com/liveTV/101?x=1", "https://www.cztv.com.evil.test/liveTV/101").forEach {
            assertFalse(CztvWebSource.recognizes(it))
        }
    }

    @Test fun moduleHashesAreDiscoveredWithoutLoadingThirdPartyScripts() {
        val html = """<script src="https://analytics.example/index-evil.js"></script><script type="module" src="/assets/index-current123.js"></script>"""
        assertEquals("https://www.cztv.com/assets/index-current123.js", CztvWebSource.appScript(html))
        val app = """["assets/directSeedingTV-next123.js","assets/directSeedingTVCopy-other.js"];import("./directSeedingTV-next123.js");"""
        assertEquals("https://www.cztv.com/assets/directSeedingTV-next123.js", CztvWebSource.liveScript(app))
    }

    @Test fun unknownOrAmbiguousModuleLayoutsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) { CztvWebSource.appScript("<script src='https://evil.test/assets/index-a.js'></script>") }
        assertThrows(IllegalArgumentException::class.java) { CztvWebSource.liveScript("import('./directSeedingTVCopy-a.js')") }
        assertThrows(IllegalArgumentException::class.java) { CztvWebSource.liveScript("['./directSeedingTV-a.js','./directSeedingTV-b.js']") }
    }

    @Test fun authParametersMustComeFromTheCurrentCdnAuthObject() {
        assertEquals(mapOf("zwebl06.cztv.com" to "public-test-key"), CztvWebSource.publicAuthParameters(live))
        assertThrows(IllegalArgumentException::class.java) { CztvWebSource.publicAuthParameters("const x={\"zwebl06.cztv.com\":{key:\"public-test-key\"}}") }
        assertThrows(IllegalArgumentException::class.java) { CztvWebSource.publicAuthParameters("cdnAuthParams:{\"zwebl06.cztv.com.evil.test\":{key:\"public-test-key\"}}") }
    }

    @Test fun onlyTheCorrectPublicChannelAnd720pQualityAreAccepted() {
        assertEquals(listOf(base), CztvWebSource.public720pUrls(info()))
        listOf(info(id = "102"), info(quality = "1080P"), info(vip = "premium"), info().replace("浙江卫视", "其他频道")).forEach {
            assertThrows(RuntimeException::class.java) { CztvWebSource.public720pUrls(it) }
        }
    }

    @Test fun unsignedMediaUrlsMustBeExactOfficialHttpsLocations() {
        listOf("http://zwebl06.cztv.com/live/channel01720Pnew.m3u8", "https://zwebl06.cztv.com.evil.test/live/channel01720Pnew.m3u8", "$base?auth_key=old", "https://user@zwebl06.cztv.com/live/channel01720Pnew.m3u8", base.replace("720P", "1080P")).forEach {
            assertThrows(RuntimeException::class.java) { CztvWebSource.public720pUrls(info(it)) }
        }
    }

    @Test fun signatureUsesMillisecondsAndPreservesLeadingZeroHexDigits() {
        val actual = CztvWebSource.signed720pUrl(base, CztvWebSource.publicAuthParameters(live), 1028L)
        assertEquals("$base?auth_key=1028-0-0-04f670c1c762652bb6684c44cdc9c718", actual)
        assertTrue(CztvWebSource.permitsFetch(actual))
    }

    @Test fun aMissingHostParameterDoesNotReuseAnotherHostsValue() {
        assertThrows(IllegalArgumentException::class.java) { CztvWebSource.signed720pUrl(base, mapOf("zwebl04.cztv.com" to "public-test-key"), 1028L) }
    }

    @Test fun officialRequestAllowlistRejectsHostTricksAndUnrelatedPages() {
        assertTrue(CztvWebSource.permitsFetch(CztvWebSource.PLAY_INFO_URL))
        listOf("https://www.cztv.com/other", "https://www.cztv.com/assets/other.js", "https://user@www.cztv.com/assets/index-a.js", "https://www.cztv.com:444/assets/index-a.js", "https://www.cztv.com.evil.test/assets/index-a.js").forEach {
            assertFalse(CztvWebSource.permitsFetch(it))
        }
    }

    @Test fun playlistValidationAcceptsOnlyPlainOfficial720pSegments() {
        val good = "#EXTM3U\n#EXT-X-TARGETDURATION:8\n#EXTINF:8.0,\nchannel01720Pnew/20261025/123.ts\n"
        val signed = CztvWebSource.signed720pUrl(base, CztvWebSource.publicAuthParameters(live), 1028L)
        CztvWebSource.validatePlain720pPlaylist(good, signed)
        listOf(good.replace("#EXTINF", "#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF"), good.replace("channel01720Pnew/20261025/123.ts", "https://evil.test/123.ts"), "<html>error</html>").forEach {
            assertThrows(IllegalArgumentException::class.java) { CztvWebSource.validatePlain720pPlaylist(it, signed) }
        }
    }

    @Test fun cookiesOrAccountHeadersAreNotAcceptedForThePublicEntry() {
        CztvWebSource.validateEntryHeaders(mapOf("user-agent" to "TV", "Referer" to CztvWebSource.ENTRY_URL))
        listOf("Cookie", "Authorization", "SessionId").forEach { header ->
            assertThrows(IllegalArgumentException::class.java) { CztvWebSource.validateEntryHeaders(mapOf(header to "private")) }
        }
    }
}
