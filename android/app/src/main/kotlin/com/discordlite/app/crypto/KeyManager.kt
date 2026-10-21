package com.discordlite.app.crypto

import android.util.Base64
import com.discordlite.app.data.local.LocalDatabase
import com.discordlite.app.data.local.metaGet
import com.discordlite.app.data.local.metaSet
import com.discordlite.app.data.remote.DiscordLiteApi
import com.discordlite.app.data.remote.KyberPreKeyBody
import com.discordlite.app.data.remote.OneTimePreKeyBody
import com.discordlite.app.data.remote.SignedPreKeyBody
import com.discordlite.app.data.remote.TopUpKeysBody
import com.discordlite.app.data.remote.UploadKeysBody
import com.discordlite.app.data.remote.apiCall
import com.discordlite.app.data.remote.apiCallUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.util.KeyHelper

/**
 * Creates this install's Signal identity and publishes the PUBLIC half to the server.
 *
 * - First sign-in: identity key pair, a signed pre-key, a last-resort Kyber (post-quantum) key,
 *   and batches of one-time keys. Private keys stay in the encrypted local database.
 * - Later: when the server's stock of one-time keys runs low, generate and upload more.
 */
class KeyManager(
    private val api: DiscordLiteApi,
    private val store: SignalStore,
    private val local: LocalDatabase,
) {
    private val lock = Mutex()

    suspend fun ensurePublished() = lock.withLock {
        withContext(Dispatchers.Default) {
            val db = local.db
            val identity = store.localIdentity() ?: run {
                val pair = IdentityKeyPair.generate()
                val registrationId = KeyHelper.generateRegistrationId(false)
                store.saveLocalIdentity(pair, registrationId)
                db.metaSet(KEYS_PUBLISHED, "0")
                pair to registrationId
            }
            if (db.metaGet(KEYS_PUBLISHED) != "1") {
                apiCallUnit { api.uploadKeys(fullUpload(identity.first, identity.second)) }
                db.metaSet(KEYS_PUBLISHED, "1")
            } else {
                topUpIfLow(identity.first)
            }
        }
    }

    private suspend fun topUpIfLow(identity: IdentityKeyPair) {
        val counts = apiCall { api.keyCounts() }
        val oneTime = if (counts.oneTime < LOW_WATER) oneTimeKeys(BATCH_ONE_TIME) else emptyList()
        val kyber = if (counts.kyber < LOW_WATER_KYBER) kyberKeys(identity, BATCH_KYBER) else emptyList()
        if (oneTime.isNotEmpty() || kyber.isNotEmpty()) {
            apiCallUnit { api.topUpKeys(TopUpKeysBody(kyberPreKeys = kyber, oneTimePreKeys = oneTime)) }
        }
    }

    private fun fullUpload(identity: IdentityKeyPair, registrationId: Int): UploadKeysBody {
        val now = System.currentTimeMillis()
        val signedPair = ECKeyPair.generate()
        val signedId = nextId(NEXT_SIGNED_ID)
        val signed = SignedPreKeyRecord(signedId, now, signedPair, identity.privateKey.calculateSignature(signedPair.publicKey.serialize()))
        store.storeSignedPreKey(signedId, signed)

        val lastResortPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val lastResortId = nextId(NEXT_KYBER_ID)
        val lastResort = KyberPreKeyRecord(lastResortId, now, lastResortPair, identity.privateKey.calculateSignature(lastResortPair.publicKey.serialize()))
        store.storeKyberPreKey(lastResortId, lastResort, lastResort = true)

        return UploadKeysBody(
            identityKey = b64(identity.publicKey.serialize()),
            registrationId = registrationId,
            signedPreKey = SignedPreKeyBody(signedId, b64(signedPair.publicKey.serialize()), b64(signed.signature)),
            lastResortKyberPreKey = KyberPreKeyBody(lastResortId, b64(lastResortPair.publicKey.serialize()), b64(lastResort.signature)),
            kyberPreKeys = kyberKeys(identity, BATCH_KYBER),
            oneTimePreKeys = oneTimeKeys(BATCH_ONE_TIME),
        )
    }

    private fun oneTimeKeys(count: Int): List<OneTimePreKeyBody> = List(count) {
        val id = nextId(NEXT_PREKEY_ID)
        val pair = ECKeyPair.generate()
        store.storePreKey(id, PreKeyRecord(id, pair))
        OneTimePreKeyBody(id, b64(pair.publicKey.serialize()))
    }

    private fun kyberKeys(identity: IdentityKeyPair, count: Int): List<KyberPreKeyBody> = List(count) {
        val id = nextId(NEXT_KYBER_ID)
        val pair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val record = KyberPreKeyRecord(id, System.currentTimeMillis(), pair, identity.privateKey.calculateSignature(pair.publicKey.serialize()))
        store.storeKyberPreKey(id, record, lastResort = false)
        KyberPreKeyBody(id, b64(pair.publicKey.serialize()), b64(record.signature))
    }

    /** Monotonic key ids (1…16,777,215), so a new batch never collides with an old one. */
    private fun nextId(counter: String): Int {
        val db = local.db
        val next = (db.metaGet(counter)?.toInt() ?: 1)
        db.metaSet(counter, (if (next >= MAX_ID) 1 else next + 1).toString())
        return next
    }

    private companion object {
        const val KEYS_PUBLISHED = "keys_published"
        const val NEXT_SIGNED_ID = "next_signed_id"
        const val NEXT_KYBER_ID = "next_kyber_id"
        const val NEXT_PREKEY_ID = "next_prekey_id"
        const val BATCH_ONE_TIME = 50
        const val BATCH_KYBER = 20
        const val LOW_WATER = 15
        const val LOW_WATER_KYBER = 5
        const val MAX_ID = 0xFFFFFF
    }
}

fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
fun unb64(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
