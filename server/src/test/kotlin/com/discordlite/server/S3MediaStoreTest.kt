package com.discordlite.server

import com.discordlite.server.media.S3MediaStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

/**
 * Talks to real S3 storage (SeaweedFS from docker-compose). Runs only when S3_ACCESS_KEY and
 * S3_SECRET_KEY are set, e.g. `set -a; . ../.env; set +a; ./gradlew test --tests '*S3MediaStore*'`;
 * skipped otherwise (as in CI). Uses the real bucket under check/ (an extra bucket would use up
 * SeaweedFS volume slots) and deletes what it writes.
 */
class S3MediaStoreTest {
    @Test
    fun `upload, download and delete against real storage`() = runBlocking {
        val access = System.getenv("S3_ACCESS_KEY")
        val secret = System.getenv("S3_SECRET_KEY")
        assumeTrue(!access.isNullOrBlank() && !secret.isNullOrBlank(), "no S3 keys in the environment")
        val store = S3MediaStore(System.getenv("S3_ENDPOINT") ?: "http://127.0.0.1:8333", System.getenv("S3_BUCKET") ?: "discordlite-media", access, secret)
        store.ensureBucket()

        val key = "check/${UUID.randomUUID()}.jpg"
        val bytes = ByteArray(5000) { (it % 251).toByte() }
        store.put(key, bytes, "image/jpeg")
        assertContentEquals(bytes, store.get(key))
        store.delete(key)
        assertNull(store.get(key))
    }
}
