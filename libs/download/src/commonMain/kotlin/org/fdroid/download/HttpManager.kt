package org.fdroid.download

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.ProxyConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.basicAuth
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders.ContentType
import io.ktor.http.HttpHeaders.ETag
import io.ktor.http.HttpHeaders.LastModified
import io.ktor.http.HttpHeaders.Range
import io.ktor.http.HttpMessageBuilder
import io.ktor.http.HttpStatusCode.Companion.NotFound
import io.ktor.http.HttpStatusCode.Companion.PartialContent
import io.ktor.http.Url
import io.ktor.http.contentLength
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.exhausted
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import mu.KotlinLogging
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayOutputStream
import kotlin.coroutines.cancellation.CancellationException

internal expect fun getHttpClientEngineFactory(customDns: Dns?): HttpClientEngineFactory<*>

public open class HttpManager @JvmOverloads constructor(
    private val userAgent: String,
    queryString: String? = null,
    proxyConfig: ProxyConfig? = null,
    customDns: Dns? = null,
    private val mirrorParameterManager: MirrorParameterManager? = null,
    private val highTimeouts: Boolean = false,
    private val mirrorChooser: MirrorChooser = MirrorChooserWithParameters(mirrorParameterManager),
    private val httpClientEngineFactory: HttpClientEngineFactory<*> = getHttpClientEngineFactory(
        customDns
    ),
) {

    public companion object {
        internal val log = KotlinLogging.logger {}
        internal const val READ_BUFFER = 8 * 1024
        private const val TIMEOUT_MILLIS_HIGH = 300_000L
        public fun isInvalidHttpUrl(url: String): Boolean = url.toHttpUrlOrNull() == null
    }

    private var httpClient = getNewHttpClient(proxyConfig)

    /**
     * Only exists because KTor doesn't keep a reference to the proxy its client uses.
     * Should only get set in [getNewHttpClient].
     */
    internal var currentProxy: ProxyConfig? = null
        private set

    private val parameters = queryString?.split('&')?.map { p ->
        val (key, value) = p.split('=')
        Pair(key, value)
    }

    private fun getNewHttpClient(proxyConfig: ProxyConfig? = null): HttpClient {
        currentProxy = proxyConfig
        return HttpClient(httpClientEngineFactory) {
            followRedirects = false
            expectSuccess = true
            engine {
                pipelining = true
                proxy = proxyConfig
            }
            install(UserAgent) {
                agent = userAgent
            }
            install(HttpTimeout) {
                if (highTimeouts || proxyConfig.isTor()) {
                    connectTimeoutMillis = TIMEOUT_MILLIS_HIGH
                    socketTimeoutMillis = TIMEOUT_MILLIS_HIGH
                    requestTimeoutMillis = TIMEOUT_MILLIS_HIGH
                }
            }
        }
    }

    /**
     * Performs a HEAD request and returns [HeadInfo].
     *
     * This is useful for checking if the repository index has changed before downloading it again.
     * However, due to non-standard ETags on mirrors, change detection is unreliable.
     */
    @Throws(NotFoundException::class)
    public suspend fun head(request: DownloadRequest, eTag: String? = null): HeadInfo? {
        val response: HttpResponse = try {
            mirrorChooser.mirrorRequest(request) { mirror, url ->
                resetProxyIfNeeded(request.proxy, mirror)
                log.debug { "HEAD $url" }
                httpClient.head(url) {
                    addQueryParameters()
                    // add authorization header from username / password if set
                    basicAuth(request)
                    // increase connect timeout if using Tor mirror
                    if (mirror.isOnion()) timeout { connectTimeoutMillis = 10_000 }
                }
            }
        } catch (e: ResponseException) {
            log.warn { "Error getting HEAD: ${e.response.status}" }
            if (e.response.status == NotFound) throw NotFoundException()
            return null
        }
        val contentLength = response.contentLength()
        val lastModified = response.headers[LastModified]
        if (eTag != null && response.headers[ETag] == eTag) {
            return HeadInfo(false, response.headers[ETag], contentLength, lastModified)
        }
        return HeadInfo(true, response.headers[ETag], contentLength, lastModified)
    }

    @OptIn(InternalAPI::class)
    @JvmOverloads
    @Throws(ResponseException::class, NoResumeException::class, CancellationException::class)
    public suspend fun get(
        request: DownloadRequest,
        skipFirstBytes: Long? = null,
        receiver: BytesReceiver,
    ) {
        // remember what we've read already, so we can pass it to the next mirror if needed
        var skipBytes = skipFirstBytes ?: 0L
        mirrorChooser.mirrorRequest(request) { mirror, url ->
            getHttpStatement(request, mirror, url, skipBytes).execute { response ->
                val contentLength = response.contentLength()
                if (skipBytes > 0L && response.status != PartialContent) {
                    throw NoResumeException()
                }
                val channel: ByteReadChannel = response.bodyAsChannel()
                val readBufferSize = DEFAULT_BUFFER_SIZE.toLong() * 8
                while (!channel.exhausted()) {
                    val packet = channel.readRemaining(readBufferSize)
                    val readBytes = packet.readByteArray()
                    receiver.receive(readBytes, contentLength)
                    skipBytes += readBytes.size
                }
            }
        }
    }

    private suspend fun getHttpStatement(
        request: DownloadRequest,
        mirror: Mirror,
        url: Url,
        skipFirstBytes: Long,
    ): HttpStatement {
        resetProxyIfNeeded(request.proxy, mirror)
        log.debug { "GET $url" }
        return httpClient.prepareGet(url) {
            addQueryParameters()
            // add authorization header from username / password if set
            basicAuth(request)
            // increase connect timeout if using Tor mirror
            if (mirror.isOnion()) timeout { connectTimeoutMillis = 20_000 }
            // add range header if set
            if (skipFirstBytes > 0) header(Range, "bytes=$skipFirstBytes-")
        }
    }

    /**
     * Returns a [ByteChannel] for streaming download.
     */
    internal suspend fun getChannel(
        request: DownloadRequest,
        skipFirstBytes: Long? = null,
    ): ByteReadChannel {
        // TODO check if closed
        return mirrorChooser.mirrorRequest(request) { mirror, url ->
            getHttpStatement(request, mirror, url, skipFirstBytes ?: 0L).body()
        }
    }

    /**
     * Same as [get], but returns all bytes.
     * Use this only when you are sure that a response will be small.
     * Thus, this is intentionally visible internally only.
     * Does not use [getChannel] so, it gets the [NoResumeException] as in the public API.
     */
    internal suspend fun getBytes(
        request: DownloadRequest,
        skipFirstBytes: Long? = null,
    ): ByteArray {
        val outputStream = ByteArrayOutputStream()
        outputStream.use {
            get(request, skipFirstBytes) { bytes, _ ->
                it.write(bytes)
            }
        }
        return outputStream.toByteArray()
    }

    public suspend fun post(url: String, json: String, proxy: ProxyConfig? = null) {
        resetProxyIfNeeded(proxy)
        httpClient.post {
            addQueryParameters()
            url(url)
            header(ContentType, "application/json; utf-8")
            setBody(json)
        }
    }

    private fun resetProxyIfNeeded(proxyConfig: ProxyConfig?, mirror: Mirror? = null) {
        // force no-proxy when trying to hit a local mirror
        val newProxy = if (mirror.isLocal() && proxyConfig != null) {
            if (currentProxy != null) log.debug {
                "Forcing mirror to null, because mirror is local: $mirror"
            }
            null
        } else proxyConfig
        if (currentProxy != newProxy) {
            log.debug { "Switching proxy from [$currentProxy] to [$newProxy]" }
            httpClient.close()
            httpClient = getNewHttpClient(newProxy)
        }
    }

    private fun HttpMessageBuilder.basicAuth(request: DownloadRequest) {
        // non-null if hasCredentials is true
        if (request.hasCredentials) basicAuth(request.username!!, request.password!!)
    }

    private fun HttpRequestBuilder.addQueryParameters() {
        // add query string parameters if existing
        this@HttpManager.parameters?.forEach { (key, value) ->
            parameter(key, value)
        }
    }
}

public fun interface BytesReceiver {
    public suspend fun receive(bytes: ByteArray, numTotalBytes: Long?)
}

/**
 * Thrown if we tried to resume a download, but the current mirror server does not offer resuming.
 */
public class NoResumeException : Exception()

/**
 * Thrown when a file was not found.
 * Catching this is useful when checking if a new index version exists
 * and then falling back to an older version.
 */
public class NotFoundException(e: Throwable? = null) : Exception(e)
