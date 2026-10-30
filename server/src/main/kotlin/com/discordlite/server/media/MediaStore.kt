package com.discordlite.server.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Where uploaded files (story photos, later posts and reels) live. Keys are opaque names we choose. */
interface MediaStore {
    suspend fun put(key: String, bytes: ByteArray, contentType: String)
    suspend fun get(key: String): ByteArray?
    suspend fun delete(key: String)
}

/** Keeps files in memory: for tests, and when no storage is configured. */
class InMemoryMediaStore : MediaStore {
    private val files = ConcurrentHashMap<String, ByteArray>()
    override suspend fun put(key: String, bytes: ByteArray, contentType: String) {
        files[key] = bytes
    }
    override suspend fun get(key: String): ByteArray? = files[key]
    override suspend fun delete(key: String) {
        files.remove(key)
    }
    val size get() = files.size
}

/**
 * Files in an S3-compatible bucket — SeaweedFS in docker-compose, but any S3 works. Requests are
 * signed with AWS Signature V4 by hand (a few lines instead of a whole SDK). The bucket is private:
 * the app never gets direct links; files are served through our API after a permission check.
 */
class S3MediaStore(
    private val endpoint: String,
    private val bucket: String,
    private val accessKey: String,
    private val secretKey: String,
    private val region: String = "us-east-1",
) : MediaStore {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val host = URI(endpoint).let { if (it.port == -1) it.host else "${it.host}:${it.port}" }

    /** Creates the bucket if it isn't there yet (fine to call on every start). */
    suspend fun ensureBucket() {
        val response = send("PUT", "/$bucket", ByteArray(0), null)
        if (response.statusCode() !in 200..299 && !response.body().decodeToString().contains("BucketAlready")) {
            LoggerFactory.getLogger(S3MediaStore::class.java).warn("Couldn't create bucket {}: HTTP {}", bucket, response.statusCode())
        }
    }

    override suspend fun put(key: String, bytes: ByteArray, contentType: String) {
        val response = send("PUT", path(key), bytes, contentType)
        check(response.statusCode() in 200..299) { "Storage upload failed: HTTP ${response.statusCode()}" }
    }

    override suspend fun get(key: String): ByteArray? {
        val response = send("GET", path(key), ByteArray(0), null)
        return when (response.statusCode()) {
            in 200..299 -> response.body()
            404 -> null
            else -> error("Storage download failed: HTTP ${response.statusCode()}")
        }
    }

    override suspend fun delete(key: String) {
        send("DELETE", path(key), ByteArray(0), null)
    }

    private fun path(key: String): String {
        require(key.all { it.isLetterOrDigit() || it in "-_./" }) { "Unsafe storage key" }
        return "/$bucket/$key"
    }

    private suspend fun send(method: String, path: String, body: ByteArray, contentType: String?): HttpResponse<ByteArray> = withContext(Dispatchers.IO) {
        val now = ZonedDateTime.now(ZoneOffset.UTC)
        val amzDate = now.format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
        val day = amzDate.take(8)
        val payloadHash = hex(sha256(body))
        val headers = sortedMapOf("host" to host, "x-amz-content-sha256" to payloadHash, "x-amz-date" to amzDate)
        if (contentType != null) headers["content-type"] = contentType
        val signedHeaders = headers.keys.joinToString(";")
        val canonical = listOf(method, path, "", headers.entries.joinToString("") { "${it.key}:${it.value}\n" }, signedHeaders, payloadHash).joinToString("\n")
        val scope = "$day/$region/s3/aws4_request"
        val toSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n${hex(sha256(canonical.toByteArray()))}"
        var key = hmac("AWS4$secretKey".toByteArray(), day)
        key = hmac(key, region)
        key = hmac(key, "s3")
        key = hmac(key, "aws4_request")
        val signature = hex(hmac(key, toSign))

        val request = HttpRequest.newBuilder(URI(endpoint.trimEnd('/') + path))
            .timeout(Duration.ofSeconds(30))
            .header("x-amz-content-sha256", payloadHash)
            .header("x-amz-date", amzDate)
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=$accessKey/$scope, SignedHeaders=$signedHeaders, Signature=$signature")
            .apply { if (contentType != null) header("Content-Type", contentType) }
            .method(method, if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        http.send(request, HttpResponse.BodyHandlers.ofByteArray())
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun hmac(key: ByteArray, data: String) = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray())
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
