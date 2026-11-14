package com.ascolta.app

import android.app.Application
import com.ascolta.app.net.*
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import kotlinx.coroutines.*
import okhttp3.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NetworkLoadingTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var directory: File
    private lateinit var base: String
    private val requests = AtomicInteger()
    private val version = AtomicInteger(1)
    private val conditional = CopyOnWriteArrayList<String?>()
    private val bytes = AtomicInteger()
    private var behavior: ((RecordedRequest) -> MockResponse)? = null
    private fun rss(title: String = "Show ${version.get()}", language: String = "<language>fr</language>") = """
        <rss version="2.0" xmlns:podcast="https://podcastindex.org/namespace/1.0"><channel><title>$title</title>$language
        <item><title>Episode one</title><guid>1</guid><enclosure url="https://example.test/1.mp3" type="audio/mpeg"/><podcast:transcript url="https://example.test/1.vtt" type="text/vtt"/></item>
        <item><title>Episode two</title><guid>2</guid><enclosure url="https://example.test/2.mp3" type="audio/mpeg"/></item>
        </channel></rss>
    """.trimIndent()
    private fun send(body: String, code: Int = 200, maxAge: Int = 0): MockResponse {
        if (code != 304) bytes.addAndGet(body.toByteArray().size)
        return MockResponse().setResponseCode(code).addHeader("Cache-Control", "public, max-age=$maxAge").apply {
            if (code != 304) setBody(body)
        }
    }
    @Before fun setup() {
        directory = File(RuntimeEnvironment.getApplication().cacheDir, "network-${System.nanoTime()}").apply { mkdirs() }
        server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.incrementAndGet()
                conditional += request.getHeader("If-None-Match")
                behavior?.let { return it(request) }
                val etag = "\"version-${version.get()}\""
                return send(rss(), if (request.getHeader("If-None-Match") == etag) 304 else 200).addHeader("ETag", etag)
            }
        }
        server.start()
        base = server.url("/").toString().trimEnd('/')
        client = OkHttpClient.Builder().cache(Cache(File(directory, "http"), 2L * 1024 * 1024)).build()
    }
    @After fun teardown() {
        client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.cache?.close()
        server.shutdown(); directory.deleteRecursively()
    }
    @Test fun simultaneousOpensShareOneFetchAndKeepAllEpisodeMetadata() = runBlocking {
        val loader = FeedLoader(client)
        val feeds = (1..8).map { async { loader.load("$base/rss") } }.awaitAll()
        assertEquals(1, requests.get())
        assertTrue(feeds.all { it === feeds.first() })
        assertEquals(2, feeds.first().episodes.size)
        assertEquals("https://example.test/1.vtt", feeds.first().episodes.first().transcriptUrl)
    }
    @Test fun unchangedRefreshUsesEtagAndDoesNotDownloadOrParseTheBodyAgain() = runBlocking {
        var now = 0L
        val loader = FeedLoader(client, now = { now })
        val first = loader.load("$base/rss")
        val size = bytes.get()
        now = 301_000
        assertSame(first, loader.load("$base/rss"))
        assertSame(first, loader.load("$base/rss", refresh = true))
        assertEquals(3, requests.get())
        assertEquals(listOf(null, "\"version-1\"", "\"version-1\""), conditional.toList())
        assertEquals(size, bytes.get())
    }
    @Test fun explicitRefreshSeesChangedEpisodeData() = runBlocking {
        val loader = FeedLoader(client)
        assertEquals("Show 1", loader.load("$base/rss").title)
        version.set(2)
        assertEquals("Show 2", loader.load("$base/rss", refresh = true).title)
        assertEquals(2, requests.get())
    }
    @Test fun onlyThreeParsedFeedsAreRetained() = runBlocking {
        val loader = FeedLoader(client)
        val first = loader.load("$base/1")
        (2..4).forEach { loader.load("$base/$it") }
        assertNotSame(first, loader.load("$base/1"))
        assertEquals(5, requests.get())
    }
    @Test fun serverErrorsDoNotBecomeCachedFeeds() = runBlocking {
        behavior = { e -> send("error", 503) }
        val loader = FeedLoader(client)
        assertTrue(runCatching { loader.load("$base/rss") }.isFailure)
        behavior = null
        assertEquals("Show 1", loader.load("$base/rss").title)
    }
    @Test fun cancelDuringBodyReadClosesTheCallPromptly() = runBlocking {
        val failed = CountDownLatch(1)
        val cancelClient = client.newBuilder().eventListener(object : EventListener() {
            override fun callFailed(call: Call, ioe: java.io.IOException) { failed.countDown() }
        }).build()
        behavior = { MockResponse().setBody(rss()).setBodyDelay(5, TimeUnit.SECONDS) }
        val started = System.nanoTime()
        assertTrue(runCatching { withTimeout(150) { cancelClient.fetchString("$base/slow") } }.exceptionOrNull() is TimeoutCancellationException)
        assertTrue("Canceled socket should finish without waiting for the server", failed.await(1, TimeUnit.SECONDS))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_500)
    }
    @Test fun transientLanguageProbeFailuresCanRecover() = runBlocking {
        behavior = { e -> send("busy", 429) }
        val language = FeedLanguage(client)
        assertNull(language.of("$base/rss"))
        behavior = null
        assertEquals("fr", language.of("$base/rss"))
        assertFalse(language.isDead("$base/rss"))
    }
    @Test fun languageProbeDoesNotUseAnEpisodeLanguageAsTheShowLanguage() = runBlocking {
        behavior = { e -> send(rss(language = "").replace("<guid>1</guid>", "<guid>1</guid><language>ja</language>")) }
        assertNull(FeedLanguage(client).of("$base/rss"))
    }
    @Test fun identicalDirectoryRequestsAreCoalescedAndDiskCacheSurvivesANewDirectory() = runBlocking {
        behavior = { e -> send("""{"results":[{"collectionName":"French show","feedUrl":"$base/rss","artistName":"Author"}]}""", maxAge = 3600) }
        val rewrite = client.newBuilder().addInterceptor { chain ->
            val old = chain.request().url
            chain.proceed(chain.request().newBuilder().url("$base${old.encodedPath}?${old.encodedQuery.orEmpty()}").build())
        }.build()
        val directory = PodcastDirectory(rewrite)
        val found = (1..12).map { async { directory.search("français", "FR") } }.awaitAll()
        assertEquals(1, requests.get())
        assertTrue(found.all { it.single().title == "French show" })
        assertEquals("French show", PodcastDirectory(rewrite).search("français", "FR").single().title)
        assertEquals(1, requests.get())
    }
    @Test fun cachedRecommendationsRespectSettingsKeysAndExpire() {
        var now = 0L
        val cache = DiscoveryCache(File(directory, "recommendations")) { now }
        val item = DirectoryResult("French show", "Author", "$base/rss", null, "Education", language = "fr")
        cache.write("fr|B1|science", listOf(item))
        assertEquals(listOf(item), cache.read("fr|B1|science"))
        assertTrue(cache.read("ja|B1|science").isEmpty())
        now = 7 * 60 * 60_000L
        assertTrue(cache.read("fr|B1|science").isEmpty())
    }
    @Test fun recommendationCacheIsBoundedAndIgnoresBrokenFiles() {
        val folder = File(directory, "recommendations")
        val cache = DiscoveryCache(folder)
        val items = (1..20).map { DirectoryResult("Show $it", null, "$base/$it", null, null) }
        repeat(12) { cache.write("key$it", items) }
        assertTrue(folder.listFiles()!!.count { it.extension == "json" } <= 8)
        assertEquals(6, cache.read("key11").size)
        folder.listFiles()!!.forEach { it.writeText("broken") }
        assertTrue(cache.read("key11").isEmpty())
    }
}
