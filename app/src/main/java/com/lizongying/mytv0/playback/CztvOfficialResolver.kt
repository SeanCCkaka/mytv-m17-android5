package com.lizongying.mytv0.playback

import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Five bounded GETs, no cookies/cache, no JS execution and no persistent token storage. */
class CztvOfficialResolver(client: OkHttpClient) {
    private val client = client.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES).cache(null)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(6, TimeUnit.SECONDS).connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS).writeTimeout(4, TimeUnit.SECONDS).build()

    fun resolve(completion: (Result<String>) -> Unit): Resolution = Resolution(client, completion).also { it.start() }

    class Resolution internal constructor(
        private val client: OkHttpClient,
        private val completion: (Result<String>) -> Unit
    ) {
        private val finished = AtomicBoolean(false)
        private val currentCall = AtomicReference<Call?>()

        internal fun start() {
            fetch(CztvWebSource.ENTRY_URL, 256_000) { html ->
                fetch(CztvWebSource.appScript(html), 3_000_000) { app ->
                    fetch(CztvWebSource.liveScript(app), 256_000) { live ->
                        val parameters = CztvWebSource.publicAuthParameters(live)
                        fetch(CztvWebSource.PLAY_INFO_URL, 128_000) { info ->
                            val urls = CztvWebSource.public720pUrls(info)
                            val url = urls.firstOrNull { java.net.URI(it).host in parameters }
                                ?: throw IOException("Official CDN unavailable")
                            val resolved = CztvWebSource.signed720pUrl(url, parameters, System.currentTimeMillis())
                            fetch(resolved, 256_000) { playlist ->
                                CztvWebSource.validatePlain720pPlaylist(playlist, resolved)
                                finish(Result.success(resolved))
                            }
                        }
                    }
                }
            }
        }

        fun cancel() {
            finished.set(true)
            currentCall.getAndSet(null)?.cancel()
        }

        private fun finish(result: Result<String>) {
            if (finished.compareAndSet(false, true)) completion(result)
        }

        private fun fetch(url: String, maxBytes: Int, receive: (String) -> Unit) {
            if (finished.get()) return
            require(CztvWebSource.permitsFetch(url)) { "Unexpected official resource" }
            val request = Request.Builder().url(url)
                .header("User-Agent", CztvWebSource.USER_AGENT)
                .header("Referer", CztvWebSource.ENTRY_URL).header("Cache-Control", "no-cache").get().build()
            val call = client.newCall(request)
            currentCall.set(call)
            if (finished.get()) { call.cancel(); return }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    // The transport exception can contain a signed URL. Do not log or forward it.
                    finish(Result.failure(IOException("Official source request failed")))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (finished.get()) return
                        try {
                            require(response.isSuccessful && response.request.url.toString() == url)
                            val body = response.body ?: throw IOException("Official source response missing")
                            require(body.contentLength() <= maxBytes || body.contentLength() == -1L)
                            val bytes = ByteArrayOutputStream()
                            body.byteStream().use { input ->
                                val buffer = ByteArray(8_192)
                                while (true) {
                                    if (finished.get()) return
                                    val count = input.read(buffer)
                                    if (count == -1) break
                                    require(bytes.size() + count <= maxBytes)
                                    bytes.write(buffer, 0, count)
                                }
                            }
                            if (!finished.get()) receive(bytes.toString("UTF-8"))
                        } catch (_: Exception) {
                            finish(Result.failure(IOException("Official source format or availability changed")))
                        }
                    }
                }
            })
        }
    }
}
