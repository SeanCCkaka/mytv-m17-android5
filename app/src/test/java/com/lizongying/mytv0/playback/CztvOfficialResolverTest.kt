package com.lizongying.mytv0.playback

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CztvOfficialResolverTest {
    @Test fun publicFlowRefreshesAllFiveResourcesWithoutSendingCookies() {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertTrue(CztvWebSource.permitsFetch(request.url.toString()))
            assertNull(request.header("Cookie"))
            assertEquals(CztvWebSource.ENTRY_URL, request.header("Referer"))
            val text = when (calls.incrementAndGet()) {
                1 -> """<script type="module" src="/assets/index-fixture.js"></script>"""
                2 -> """import("./directSeedingTV-fixture.js")"""
                3 -> """cdnAuthParams:{"zwebl06.cztv.com":{key:"public-test-key"}}"""
                4 -> """{"success":true,"code":200,"data":{"channelId":"101","channelName":"浙江卫视","multiBitrateStreamList":[{"bitrateCode":"720P","vipIcon":"","urlList":["https://zwebl06.cztv.com/live/channel01720Pnew.m3u8"]}]}}"""
                5 -> "#EXTM3U\n#EXTINF:8,\nchannel01720Pnew/date/1.ts\n"
                else -> error("Unexpected extra request")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(text.toResponseBody()).build()
        }.build()
        val done = CountDownLatch(1)
        val result = AtomicReference<Result<String>>()
        CztvOfficialResolver(client).resolve { result.set(it); done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(result.get().isSuccess)
        assertTrue(CztvWebSource.permitsFetch(result.get().getOrThrow()))
        assertEquals(5, calls.get())
    }

    @Test fun redirectsAreNotFollowedAndFailureCompletesOnlyOnce() {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Found")
                .header("Location", "https://evil.test/").body("".toResponseBody()).build()
        }.build()
        val done = CountDownLatch(1)
        val result = AtomicReference<Result<String>>()
        CztvOfficialResolver(client).resolve { result.set(it); done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(result.get().isFailure)
        assertEquals(1, calls.get())
    }

    @Test fun cancellationPreventsCompletionAndFurtherFetches() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val completion = CountDownLatch(1)
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet(); entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            finished.countDown()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("<script src='/assets/index-fixture.js'></script>".toResponseBody()).build()
        }.build()
        val resolution = CztvOfficialResolver(client).resolve { completion.countDown() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        resolution.cancel()
        release.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertFalse(completion.await(200, TimeUnit.MILLISECONDS))
        assertEquals(1, calls.get())
    }
}
