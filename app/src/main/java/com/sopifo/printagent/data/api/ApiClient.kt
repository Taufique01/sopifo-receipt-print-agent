package com.sopifo.printagent.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** Any failure talking to the backend. `retryable` is true for network errors, 408, 429 and 5xx. */
class ApiException(
    val httpCode: Int?,
    message: String,
    cause: Throwable? = null,
    /** The backend answered, but the body did not match the contract. */
    val malformed: Boolean = false,
) : IOException(message, cause) {
    val retryable: Boolean get() = httpCode == null || httpCode == 408 || httpCode == 429 || httpCode >= 500
    val unauthorized: Boolean get() = httpCode == 401 || httpCode == 403
    val notFound: Boolean get() = httpCode == 404 || httpCode == 410
}

data class PendingJobsResult(
    val jobs: List<PrintJobDto>,
    val invalidEntries: Int,
    /** Live bulk print batches for this device (fetched with [ApiClient.getBatch]). */
    val batches: List<String> = emptyList(),
)

/**
 * Thin, defensive client for the Sopifo Cloud print API. All calls have hard timeouts, all
 * responses are parsed leniently (unknown fields ignored), and every URL is checked against
 * [UrlPolicy] before a request is made.
 */
class ApiClient(
    private val baseUrlProvider: () -> String?,
    private val tokenProvider: () -> String?,
    private val urlPolicy: UrlPolicy,
    private val serverClock: ServerClock,
    private val listener: Listener = Listener.NONE,
    httpClient: OkHttpClient? = null,
) {
    interface Listener {
        /** true when the backend answered (any HTTP status), false on network failure. */
        fun onReachability(reachable: Boolean) {}
        fun onUnauthorized() {}
        companion object { val NONE = object : Listener {} }
    }

    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val http: OkHttpClient = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // A job burst (fetch → download → report) reuses one connection; after that the radio
        // should go idle, so don't keep sockets alive for OkHttp's default 5 minutes.
        .connectionPool(ConnectionPool(2, 15, TimeUnit.SECONDS))
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    companion object {
        const val AGENT_FEATURES = "batch"
    }

    suspend fun register(baseUrl: String, body: RegisterRequest): RegisterResponse {
        val base = urlPolicy.parseAllowed(baseUrl) ?: throw ApiException(null, "Server URL not allowed: $baseUrl")
        val element = execute(post(base, "api/devices/register", json.encodeToString(RegisterRequest.serializer(), body), auth = false))
        return decode(RegisterResponse.serializer(), unwrap(element, "data", "device"))
    }

    /** Returns true when support asked for this phone's logs (missed push fallback). */
    suspend fun heartbeat(body: HeartbeatRequest): Boolean {
        val element = execute(post(base(), "api/devices/heartbeat", json.encodeToString(HeartbeatRequest.serializer(), body)))
        return ((element as? JsonObject)?.get("upload_logs") as? JsonPrimitive)?.content == "true"
    }

    /** Uploads a gzipped log bundle; returns the reference code support finds it by. */
    suspend fun uploadLogs(reason: String, appVersion: String, gzipped: ByteArray): String {
        val url = resolve(base(), "api/devices/logs").newBuilder()
            .addQueryParameter("reason", reason)
            .addQueryParameter("app_version", appVersion)
            .build()
        val request = authorized(Request.Builder().url(url).post(gzipped.toRequestBody("application/gzip".toMediaType())), true)
        val ref = ((execute(request) as? JsonObject)?.get("ref") as? JsonPrimitive)?.takeIf { it.isString }?.content
        return ref ?: throw ApiException(200, "Log upload answered without a reference", malformed = true)
    }

    suspend fun updateFcmToken(token: String) {
        execute(post(base(), "api/devices/fcm-token", json.encodeToString(FcmTokenRequest.serializer(), FcmTokenRequest(token))))
    }

    suspend fun getJob(jobId: String): PrintJobDto {
        require(UrlPolicy.isValidJobId(jobId)) { "Invalid job id" }
        val element = execute(get(base(), "api/print-jobs/$jobId"))
        return decode(PrintJobDto.serializer(), unwrap(element, "data", "job"))
    }

    /** A bulk print batch: its waiting jobs in print order. Fetching also renders the next ones. */
    suspend fun getBatch(batchId: String): PrintBatchDto {
        require(UrlPolicy.isValidJobId(batchId)) { "Invalid batch id" }
        return decode(PrintBatchDto.serializer(), execute(get(base(), "api/print-batches/$batchId")))
    }

    /** Pending jobs for this device. Malformed entries are dropped individually, not the whole batch. */
    suspend fun getPendingJobs(): PendingJobsResult {
        val element = execute(get(base(), "api/print-jobs/pending"))
        val array: JsonArray = when (element) {
            is JsonArray -> element
            is JsonObject -> (element["jobs"] ?: element["data"]) as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        var invalid = 0
        val jobs = array.mapNotNull {
            try {
                json.decodeFromJsonElement(PrintJobDto.serializer(), it)
            } catch (_: Exception) {
                invalid++
                null
            }
        }
        val batches = ((element as? JsonObject)?.get("batches") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf(UrlPolicy::isValidJobId) }
            .orEmpty()
        return PendingJobsResult(jobs, invalid, batches)
    }

    suspend fun completeJob(jobId: String, printedAtIso: String) {
        require(UrlPolicy.isValidJobId(jobId)) { "Invalid job id" }
        execute(post(base(), "api/print-jobs/$jobId/complete", json.encodeToString(CompleteJobRequest.serializer(), CompleteJobRequest(printedAtIso))))
    }

    suspend fun failJob(jobId: String, error: String) {
        require(UrlPolicy.isValidJobId(jobId)) { "Invalid job id" }
        execute(post(base(), "api/print-jobs/$jobId/fail", json.encodeToString(FailJobRequest.serializer(), FailJobRequest(error.take(500)))))
    }

    suspend fun cancelJob(jobId: String) {
        require(UrlPolicy.isValidJobId(jobId)) { "Invalid job id" }
        execute(post(base(), "api/print-jobs/$jobId/cancel", "{}"))
    }

    /**
     * Downloads a job image with a hard size cap. The device JWT is only attached when the image
     * is served by the API host itself, never to third-party storage/CDN hosts.
     */
    suspend fun downloadImage(url: String, maxBytes: Int): ByteArray = withContext(Dispatchers.IO) {
        val target = urlPolicy.parseAllowed(url) ?: throw ApiException(null, "Image URL not allowed")
        val apiHost = baseUrlProvider()?.let { urlPolicy.parseAllowed(it) }?.host
        val builder = Request.Builder().url(target).get()
        if (apiHost != null && apiHost == target.host) tokenProvider()?.let { builder.header("Authorization", "Bearer $it") }
        val response = await(builder.build())
        response.use { resp ->
            listener.onReachability(true)
            if (!resp.isSuccessful) throw ApiException(resp.code, "Image download failed: HTTP ${resp.code}")
            val body = resp.body ?: throw ApiException(resp.code, "Empty image body")
            val declared = body.contentLength()
            if (declared > maxBytes) throw ApiException(resp.code, "Image too large: $declared bytes")
            val out = ByteArrayOutputStream(if (declared in 1..maxBytes) declared.toInt() else 64 * 1024)
            val buffer = ByteArray(16 * 1024)
            body.byteStream().use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (out.size() + n > maxBytes) throw ApiException(resp.code, "Image exceeds $maxBytes bytes")
                    out.write(buffer, 0, n)
                }
            }
            out.toByteArray()
        }
    }

    // ---- The app's own pages: Orders and Shipping (see PrintPagesModels.kt). ----

    /** One page of open orders, newest first; [status] narrows to one open status. */
    suspend fun listOrders(status: String?, page: Int, pageSize: Int): OrdersPageDto {
        val url = resolve(base(), "api/device/orders").newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("page_size", pageSize.toString())
            .apply { if (!status.isNullOrBlank()) addQueryParameter("status", status) }
            .build()
        return decode(OrdersPageDto.serializer(), execute(authorized(Request.Builder().url(url).get(), true)))
    }

    /** One page of a courier's parcels ("pathao" / "steadfast"; filter "awaiting" / "all"). */
    suspend fun listParcels(courier: String, filter: String, page: Int, pageSize: Int): ParcelsPageDto {
        val url = resolve(base(), "api/device/parcels").newBuilder()
            .addQueryParameter("courier", courier)
            .addQueryParameter("filter", filter)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("page_size", pageSize.toString())
            .build()
        return decode(ParcelsPageDto.serializer(), execute(authorized(Request.Builder().url(url).get(), true)))
    }

    suspend fun syncParcels(courier: String): SyncParcelsResponse =
        decode(
            SyncParcelsResponse.serializer(),
            execute(post(base(), "api/device/parcels/sync", json.encodeToString(SyncParcelsRequest.serializer(), SyncParcelsRequest(courier)))),
        )

    /** An order's invoice or label, drawn now (no job, no push): print it straight away. */
    suspend fun renderOrder(orderId: String, doc: String): RenderedImage {
        val url = resolve(base(), "api/device/orders").newBuilder()
            .addPathSegment(orderId).addPathSegment("render")
            .addQueryParameter("doc", doc)
            .build()
        return fetchImage(url)
    }

    /** A courier parcel's label, drawn now. */
    suspend fun renderParcel(parcelId: String): RenderedImage {
        val url = resolve(base(), "api/device/parcels").newBuilder()
            .addPathSegment(parcelId).addPathSegment("render")
            .build()
        return fetchImage(url)
    }

    /** What the app printed from its own pages: history rows and printed marks on the server. */
    suspend fun reportPrintResults(body: PrintResultsRequest) {
        execute(post(base(), "api/device/print-results", json.encodeToString(PrintResultsRequest.serializer(), body)))
    }

    private suspend fun fetchImage(url: HttpUrl): RenderedImage = withContext(Dispatchers.IO) {
        val request = authorized(Request.Builder().url(url).get(), true)
        val response = await(request)
        response.use { resp ->
            listener.onReachability(true)
            if (resp.code == 401 || resp.code == 403) listener.onUnauthorized()
            if (!resp.isSuccessful) throw ApiException(resp.code, serverError(resp.body?.string()) ?: "HTTP ${resp.code}")
            val bytes = resp.body?.bytes() ?: throw ApiException(resp.code, "Empty image")
            if (bytes.size > com.sopifo.printagent.print.PrintDataBuilder.MAX_DOWNLOAD_BYTES) {
                throw ApiException(resp.code, "Image too large: ${bytes.size} bytes")
            }
            RenderedImage(bytes, resp.header("X-Print-Printer") ?: "label")
        }
    }

    /** The backend's `{ "error": "…" }` message, for errors the seller can act on. */
    private fun serverError(text: String?): String? = try {
        ((text?.let { json.parseToJsonElement(it) } as? JsonObject)?.get("error") as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.take(200)
    } catch (_: Exception) {
        null
    }

    private fun base(): HttpUrl {
        val raw = baseUrlProvider() ?: throw ApiException(null, "Device not registered")
        return urlPolicy.parseAllowed(raw) ?: throw ApiException(null, "Server URL not allowed")
    }

    private fun resolve(base: HttpUrl, path: String): HttpUrl =
        base.newBuilder().encodedPath("/").addPathSegments(path).build()

    private fun get(base: HttpUrl, path: String): Request =
        authorized(Request.Builder().url(resolve(base, path)).get(), true)

    private fun post(base: HttpUrl, path: String, body: String, auth: Boolean = true): Request =
        authorized(Request.Builder().url(resolve(base, path)).post(body.toRequestBody(jsonMedia)), auth)

    private fun authorized(builder: Request.Builder, auth: Boolean): Request {
        builder.header("Accept", "application/json")
        // Tells the backend this app can print bulk batches; older apps don't send it.
        builder.header("X-Agent-Features", AGENT_FEATURES)
        if (auth) {
            val token = tokenProvider() ?: throw ApiException(401, "No device session")
            builder.header("Authorization", "Bearer $token")
        }
        return builder.build()
    }

    /** Body reading is blocking I/O, so the whole exchange runs on the IO dispatcher whoever calls it (e.g. the UI). */
    private suspend fun execute(request: Request): JsonElement = withContext(Dispatchers.IO) {
        val response = await(request)
        response.use { resp ->
            listener.onReachability(true)
            serverClock.onServerDate(resp.header("Date"))
            val text = resp.body?.string().orEmpty()
            if (resp.code == 401 || resp.code == 403) listener.onUnauthorized()
            if (!resp.isSuccessful) {
                throw ApiException(resp.code, serverError(text) ?: "HTTP ${resp.code} for ${request.url.encodedPath}")
            }
            if (text.isBlank()) return@withContext JsonObject(emptyMap())
            try {
                json.parseToJsonElement(text)
            } catch (e: Exception) {
                throw ApiException(resp.code, "Malformed JSON from ${request.url.encodedPath}", e, malformed = true)
            }
        }
    }

    private suspend fun await(request: Request): Response = try {
        http.newCall(request).await()
    } catch (e: IOException) {
        listener.onReachability(false)
        throw if (e is ApiException) e else ApiException(null, "Network error: ${e.message}", e)
    }

    private fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, element: JsonElement): T = try {
        json.decodeFromJsonElement(serializer, element)
    } catch (e: kotlinx.serialization.SerializationException) {
        throw ApiException(200, "Response does not match contract: ${e.message?.take(120)}", e, malformed = true)
    } catch (e: IllegalArgumentException) {
        throw ApiException(200, "Response does not match contract: ${e.message?.take(120)}", e, malformed = true)
    }

    /**
     * Accepts both bare objects and `{ "<key>": {...} }` envelopes. Keys are per endpoint: a bare
     * job carries a nested `device` object, which must not be mistaken for an envelope.
     */
    private fun unwrap(element: JsonElement, vararg keys: String): JsonElement {
        if (element is JsonObject) {
            for (key in keys) {
                val inner = element[key]
                if (inner is JsonObject) return inner
            }
        }
        return element
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, r, _ -> r.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    })
}
