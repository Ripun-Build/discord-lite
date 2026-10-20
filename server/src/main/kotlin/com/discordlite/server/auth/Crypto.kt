package com.discordlite.server.auth

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val random = SecureRandom()
private val b64 = Base64.getEncoder().withoutPadding()
private val b64Decoder = Base64.getDecoder()
private val b64Url = Base64.getUrlEncoder().withoutPadding()

/**
 * Argon2id password hashing (OWASP baseline: 19 MiB memory, 2 iterations, 1 lane), stored in the
 * standard PHC string format so parameters can be raised later without breaking old hashes.
 */
object Passwords {
    private const val MEMORY_KB = 19_456
    private const val ITERATIONS = 2
    private const val PARALLELISM = 1
    private const val SALT_BYTES = 16
    private const val HASH_BYTES = 32

    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 128

    fun hash(password: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val hash = derive(password, salt, MEMORY_KB, ITERATIONS, PARALLELISM, HASH_BYTES)
        return "\$argon2id\$v=19\$m=$MEMORY_KB,t=$ITERATIONS,p=$PARALLELISM\$${b64.encodeToString(salt)}\$${b64.encodeToString(hash)}"
    }

    fun verify(password: String, encoded: String): Boolean {
        val parts = encoded.split('$')
        if (parts.size != 6 || parts[1] != "argon2id") return false
        val params = parts[3].split(',').associate { it.substringBefore('=') to it.substringAfter('=').toInt() }
        val salt = b64Decoder.decode(parts[4])
        val expected = b64Decoder.decode(parts[5])
        val actual = derive(password, salt, params.getValue("m"), params.getValue("t"), params.getValue("p"), expected.size)
        return MessageDigest.isEqual(actual, expected)
    }

    private val dummyHash by lazy { hash("timing-equaliser") }

    /** Spends the same time as a real check, so "no such email" can't be told apart by timing. */
    fun burnTime(password: String) {
        verify(password, dummyHash)
    }

    private fun derive(password: String, salt: ByteArray, memoryKb: Int, iterations: Int, parallelism: Int, length: Int): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(memoryKb)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .withSalt(salt)
            .build()
        val out = ByteArray(length)
        Argon2BytesGenerator().apply { init(params) }.generateBytes(password.toCharArray(), out)
        return out
    }
}

enum class CodePurpose { VERIFY_EMAIL, RESET_PASSWORD }

/** Six-digit email codes. Only an HMAC (keyed with a server-side pepper) ever reaches the database. */
class Codes(pepper: String) {
    private val key = SecretKeySpec(pepper.toByteArray(), "HmacSHA256")

    fun generate(): String = random.nextInt(1_000_000).toString().padStart(6, '0')

    fun hash(userId: UUID, purpose: CodePurpose, code: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(key)
            doFinal("$userId:$purpose:$code".toByteArray())
        }
}

fun randomToken(bytes: Int = 32): String = b64Url.encodeToString(ByteArray(bytes).also(random::nextBytes))

fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
