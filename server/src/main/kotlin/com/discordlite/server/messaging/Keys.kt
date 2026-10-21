package com.discordlite.server.messaging

import com.discordlite.server.common.badRequest
import com.discordlite.server.common.notFound
import com.discordlite.server.db.Db
import com.discordlite.server.db.exec
import com.discordlite.server.db.queryOne
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.util.Base64
import java.util.UUID

@Serializable data class SignedPreKeyDto(val keyId: Int, val publicKey: String, val signature: String)
@Serializable data class KyberPreKeyDto(val keyId: Int, val publicKey: String, val signature: String)
@Serializable data class OneTimePreKeyDto(val keyId: Int, val publicKey: String)

/** Everything a fresh install publishes. Replaces whatever was there before. */
@Serializable
data class UploadKeysRequest(
    val identityKey: String,
    val registrationId: Int,
    val signedPreKey: SignedPreKeyDto,
    val lastResortKyberPreKey: KyberPreKeyDto,
    val kyberPreKeys: List<KyberPreKeyDto> = emptyList(),
    val oneTimePreKeys: List<OneTimePreKeyDto> = emptyList(),
)

/** Adds more one-time keys (and optionally rotates the signed pre-key). */
@Serializable
data class TopUpKeysRequest(
    val kyberPreKeys: List<KyberPreKeyDto> = emptyList(),
    val oneTimePreKeys: List<OneTimePreKeyDto> = emptyList(),
    val signedPreKey: SignedPreKeyDto? = null,
)

@Serializable data class PreKeyCounts(val oneTime: Int, val kyber: Int)

/** What a sender needs to start an encrypted session with [userId]. */
@Serializable
data class PreKeyBundleDto(
    val userId: String,
    val registrationId: Int,
    val identityKey: String,
    val signedPreKey: SignedPreKeyDto,
    val kyberPreKey: KyberPreKeyDto,
    val oneTimePreKey: OneTimePreKeyDto? = null,
)

/**
 * Stores users' PUBLIC Signal keys and hands out pre-key bundles. Private keys never leave the
 * phone. Keys are checked for the right size here; their signatures are verified by the sender's
 * libsignal before any session is built, so a forged bundle is rejected on the device.
 */
class KeyService(private val db: Db) {

    suspend fun upload(userId: UUID, request: UploadKeysRequest) {
        val identity = decode(request.identityKey, IDENTITY_KEY_BYTES, "identity_key")
        validate(request.signedPreKey)
        validate(request.lastResortKyberPreKey)
        request.kyberPreKeys.forEach(::validate)
        request.oneTimePreKeys.forEach(::validate)
        if (request.kyberPreKeys.size > MAX_BATCH || request.oneTimePreKeys.size > MAX_BATCH) throw badRequest("too_many_keys")

        db.tx {
            exec(
                """
                INSERT INTO identity_keys (user_id, identity_key, registration_id) VALUES (?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE
                    SET identity_key = excluded.identity_key, registration_id = excluded.registration_id, updated_at = now()
                """.trimIndent(),
                userId, identity, request.registrationId,
            )
            // A full upload means a fresh install: old pre-keys belong to keys that no longer exist.
            exec("DELETE FROM kyber_prekeys WHERE user_id = ?", userId)
            exec("DELETE FROM one_time_prekeys WHERE user_id = ?", userId)
            storeSigned(userId, request.signedPreKey)
            storeKyber(userId, request.lastResortKyberPreKey, lastResort = true)
            request.kyberPreKeys.forEach { storeKyber(userId, it, lastResort = false) }
            request.oneTimePreKeys.forEach { storeOneTime(userId, it) }
        }
    }

    suspend fun topUp(userId: UUID, request: TopUpKeysRequest) {
        request.kyberPreKeys.forEach(::validate)
        request.oneTimePreKeys.forEach(::validate)
        request.signedPreKey?.let(::validate)
        if (request.kyberPreKeys.size > MAX_BATCH || request.oneTimePreKeys.size > MAX_BATCH) throw badRequest("too_many_keys")
        db.tx {
            if (!hasIdentity(userId)) throw badRequest("keys_not_registered")
            request.signedPreKey?.let { storeSigned(userId, it) }
            request.kyberPreKeys.forEach { storeKyber(userId, it, lastResort = false) }
            request.oneTimePreKeys.forEach { storeOneTime(userId, it) }
        }
    }

    suspend fun counts(userId: UUID): PreKeyCounts = db.tx {
        PreKeyCounts(
            oneTime = queryOne("SELECT count(*) AS n FROM one_time_prekeys WHERE user_id = ?", userId) { it.getInt("n") } ?: 0,
            kyber = queryOne("SELECT count(*) AS n FROM kyber_prekeys WHERE user_id = ? AND NOT last_resort", userId) { it.getInt("n") } ?: 0,
        )
    }

    /** Hands out a bundle, consuming one one-time key and one one-time Kyber key if any are left. */
    fun Connection.takeBundle(target: UUID): PreKeyBundleDto {
        val (identity, registrationId) = queryOne(
            "SELECT identity_key, registration_id FROM identity_keys WHERE user_id = ?", target,
        ) { it.getBytes("identity_key") to it.getInt("registration_id") } ?: throw notFound("no_keys")

        val signed = queryOne("SELECT key_id, public_key, signature FROM signed_prekeys WHERE user_id = ?", target) {
            SignedPreKeyDto(it.getInt("key_id"), b64(it.getBytes("public_key")), b64(it.getBytes("signature")))
        } ?: throw notFound("no_keys")

        val kyber = queryOne(
            """
            DELETE FROM kyber_prekeys
            WHERE (user_id, key_id) = (
                SELECT user_id, key_id FROM kyber_prekeys
                WHERE user_id = ? AND NOT last_resort ORDER BY key_id LIMIT 1 FOR UPDATE SKIP LOCKED
            )
            RETURNING key_id, public_key, signature
            """.trimIndent(),
            target,
        ) { it.toKyber() } ?: queryOne(
            "SELECT key_id, public_key, signature FROM kyber_prekeys WHERE user_id = ? AND last_resort",
            target,
        ) { it.toKyber() } ?: throw notFound("no_keys")

        val oneTime = queryOne(
            """
            DELETE FROM one_time_prekeys
            WHERE (user_id, key_id) = (
                SELECT user_id, key_id FROM one_time_prekeys WHERE user_id = ? ORDER BY key_id LIMIT 1 FOR UPDATE SKIP LOCKED
            )
            RETURNING key_id, public_key
            """.trimIndent(),
            target,
        ) { OneTimePreKeyDto(it.getInt("key_id"), b64(it.getBytes("public_key"))) }

        return PreKeyBundleDto(target.toString(), registrationId, b64(identity), signed, kyber, oneTime)
    }

    fun Connection.hasIdentity(userId: UUID): Boolean =
        queryOne("SELECT 1 FROM identity_keys WHERE user_id = ?", userId) { true } == true

    private fun Connection.storeSigned(userId: UUID, key: SignedPreKeyDto) = exec(
        """
        INSERT INTO signed_prekeys (user_id, key_id, public_key, signature) VALUES (?, ?, ?, ?)
        ON CONFLICT (user_id) DO UPDATE
            SET key_id = excluded.key_id, public_key = excluded.public_key, signature = excluded.signature, created_at = now()
        """.trimIndent(),
        userId, key.keyId, unb64(key.publicKey), unb64(key.signature),
    )

    private fun Connection.storeKyber(userId: UUID, key: KyberPreKeyDto, lastResort: Boolean) = exec(
        """
        INSERT INTO kyber_prekeys (user_id, key_id, public_key, signature, last_resort) VALUES (?, ?, ?, ?, ?)
        ON CONFLICT (user_id, key_id) DO UPDATE
            SET public_key = excluded.public_key, signature = excluded.signature, last_resort = excluded.last_resort
        """.trimIndent(),
        userId, key.keyId, unb64(key.publicKey), unb64(key.signature), lastResort,
    )

    private fun Connection.storeOneTime(userId: UUID, key: OneTimePreKeyDto) = exec(
        "INSERT INTO one_time_prekeys (user_id, key_id, public_key) VALUES (?, ?, ?) ON CONFLICT (user_id, key_id) DO UPDATE SET public_key = excluded.public_key",
        userId, key.keyId, unb64(key.publicKey),
    )

    private fun java.sql.ResultSet.toKyber() =
        KyberPreKeyDto(getInt("key_id"), b64(getBytes("public_key")), b64(getBytes("signature")))

    private fun validate(key: SignedPreKeyDto) {
        validateId(key.keyId)
        decode(key.publicKey, EC_KEY_BYTES, "pre_key")
        decode(key.signature, SIGNATURE_BYTES, "signature")
    }

    private fun validate(key: KyberPreKeyDto) {
        validateId(key.keyId)
        decode(key.publicKey, KYBER_KEY_BYTES, "kyber_key")
        decode(key.signature, SIGNATURE_BYTES, "signature")
    }

    private fun validate(key: OneTimePreKeyDto) {
        validateId(key.keyId)
        decode(key.publicKey, EC_KEY_BYTES, "pre_key")
    }

    private fun validateId(id: Int) {
        if (id !in 0..MAX_KEY_ID) throw badRequest("invalid_key_id")
    }

    private fun decode(value: String, expectedBytes: Int, what: String): ByteArray {
        val bytes = runCatching { unb64(value) }.getOrNull()
        if (bytes == null || bytes.size != expectedBytes) throw badRequest("invalid_$what")
        return bytes
    }

    companion object {
        const val IDENTITY_KEY_BYTES = 33 // type byte + 32-byte Curve25519 key
        const val EC_KEY_BYTES = 33
        const val SIGNATURE_BYTES = 64
        const val KYBER_KEY_BYTES = 1569 // type byte + Kyber-1024 public key
        const val MAX_KEY_ID = 0xFFFFFF
        const val MAX_BATCH = 100
    }
}

private val encoder = Base64.getEncoder()
private val decoder = Base64.getDecoder()

fun b64(bytes: ByteArray): String = encoder.encodeToString(bytes)
fun unb64(value: String): ByteArray = decoder.decode(value)
