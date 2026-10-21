package com.discordlite.app.crypto

import com.discordlite.app.data.local.LocalDatabase
import com.discordlite.app.data.local.blob
import com.discordlite.app.data.local.exec
import com.discordlite.app.data.local.int
import com.discordlite.app.data.local.queryList
import com.discordlite.app.data.local.queryOne
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.util.UUID

/**
 * libsignal's storage, backed by the encrypted local database. Called synchronously by libsignal;
 * every caller runs it off the main thread.
 *
 * Trust policy: trust on first use. If a contact's identity key later changes (new phone or
 * reinstall), the new key is accepted and [onIdentityChanged] fires so the chat can show
 * "security code changed" — the user can then compare codes.
 */
class SignalStore(
    private val local: LocalDatabase,
    private val onIdentityChanged: (address: String) -> Unit = {},
) : SignalProtocolStore {
    private val db get() = local.db

    /** Generated once per install; null until then. */
    fun localIdentity(): Pair<IdentityKeyPair, Int>? =
        db.queryOne("SELECT identity_key_pair, registration_id FROM signal_local WHERE id = 1") {
            IdentityKeyPair(it.blob("identity_key_pair")) to it.int("registration_id")
        }

    fun saveLocalIdentity(pair: IdentityKeyPair, registrationId: Int) =
        db.exec("INSERT OR REPLACE INTO signal_local (id, identity_key_pair, registration_id) VALUES (1, ?, ?)", pair.serialize(), registrationId)

    override fun getIdentityKeyPair(): IdentityKeyPair = localIdentity()?.first ?: error("No local identity yet")

    override fun getLocalRegistrationId(): Int = localIdentity()?.second ?: error("No local identity yet")

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val existing = getIdentity(address)
        if (existing == identityKey) return IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        db.exec(
            "INSERT OR REPLACE INTO signal_identities (address, identity_key, first_seen) VALUES (?, ?, ?)",
            address.name, identityKey.serialize(), System.currentTimeMillis(),
        )
        return if (existing == null) {
            IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        } else {
            onIdentityChanged(address.name)
            IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        }
    }

    override fun isTrustedIdentity(address: SignalProtocolAddress, identityKey: IdentityKey, direction: IdentityKeyStore.Direction) = true

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
        db.queryOne("SELECT identity_key FROM signal_identities WHERE address = ?", address.name) { IdentityKey(it.blob("identity_key")) }

    // One-time pre-keys

    override fun loadPreKey(preKeyId: Int): PreKeyRecord =
        db.queryOne("SELECT record FROM signal_prekeys WHERE id = ?", preKeyId) { PreKeyRecord(it.blob("record")) }
            ?: throw InvalidKeyIdException("No pre-key $preKeyId")

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) =
        db.exec("INSERT OR REPLACE INTO signal_prekeys (id, record) VALUES (?, ?)", preKeyId, record.serialize())

    override fun containsPreKey(preKeyId: Int) =
        db.queryOne("SELECT 1 FROM signal_prekeys WHERE id = ?", preKeyId) { true } == true

    override fun removePreKey(preKeyId: Int) = db.exec("DELETE FROM signal_prekeys WHERE id = ?", preKeyId)

    // Signed pre-keys

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord =
        db.queryOne("SELECT record FROM signal_signed_prekeys WHERE id = ?", signedPreKeyId) { SignedPreKeyRecord(it.blob("record")) }
            ?: throw InvalidKeyIdException("No signed pre-key $signedPreKeyId")

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> =
        db.queryList("SELECT record FROM signal_signed_prekeys") { SignedPreKeyRecord(it.blob("record")) }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) =
        db.exec("INSERT OR REPLACE INTO signal_signed_prekeys (id, record) VALUES (?, ?)", signedPreKeyId, record.serialize())

    override fun containsSignedPreKey(signedPreKeyId: Int) =
        db.queryOne("SELECT 1 FROM signal_signed_prekeys WHERE id = ?", signedPreKeyId) { true } == true

    override fun removeSignedPreKey(signedPreKeyId: Int) = db.exec("DELETE FROM signal_signed_prekeys WHERE id = ?", signedPreKeyId)

    // Kyber (post-quantum) pre-keys

    fun storeKyberPreKey(id: Int, record: KyberPreKeyRecord, lastResort: Boolean) =
        db.exec("INSERT OR REPLACE INTO signal_kyber_prekeys (id, record, last_resort) VALUES (?, ?, ?)", id, record.serialize(), if (lastResort) 1 else 0)

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord =
        db.queryOne("SELECT record FROM signal_kyber_prekeys WHERE id = ?", kyberPreKeyId) { KyberPreKeyRecord(it.blob("record")) }
            ?: throw InvalidKeyIdException("No Kyber pre-key $kyberPreKeyId")

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> =
        db.queryList("SELECT record FROM signal_kyber_prekeys") { KyberPreKeyRecord(it.blob("record")) }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) = storeKyberPreKey(kyberPreKeyId, record, lastResort = false)

    override fun containsKyberPreKey(kyberPreKeyId: Int) =
        db.queryOne("SELECT 1 FROM signal_kyber_prekeys WHERE id = ?", kyberPreKeyId) { true } == true

    /**
     * One-time Kyber keys are deleted after use. The last-resort key stays, but the same base key
     * may never be used with it twice (that would be a replayed first message).
     */
    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) {
        val lastResort = db.queryOne("SELECT last_resort FROM signal_kyber_prekeys WHERE id = ?", kyberPreKeyId) { it.int("last_resort") == 1 } ?: return
        if (!lastResort) {
            db.exec("DELETE FROM signal_kyber_prekeys WHERE id = ?", kyberPreKeyId)
            return
        }
        val seen = db.queryOne(
            "SELECT 1 FROM signal_kyber_base_keys WHERE kyber_id = ? AND signed_id = ? AND base_key = ?",
            kyberPreKeyId, signedPreKeyId, baseKey.serialize(),
        ) { true } == true
        if (seen) throw ReusedBaseKeyException()
        db.exec("INSERT INTO signal_kyber_base_keys (kyber_id, signed_id, base_key) VALUES (?, ?, ?)", kyberPreKeyId, signedPreKeyId, baseKey.serialize())
    }

    // Sessions

    override fun loadSession(address: SignalProtocolAddress): SessionRecord =
        db.queryOne("SELECT record FROM signal_sessions WHERE address = ?", address.key()) { SessionRecord(it.blob("record")) } ?: SessionRecord()

    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> =
        addresses.map { address ->
            db.queryOne("SELECT record FROM signal_sessions WHERE address = ?", address.key()) { SessionRecord(it.blob("record")) }
                ?: throw NoSessionException("No session for ${address.key()}")
        }

    override fun getSubDeviceSessions(name: String): List<Int> = emptyList() // one device per account

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) =
        db.exec("INSERT OR REPLACE INTO signal_sessions (address, record) VALUES (?, ?)", address.key(), record.serialize())

    override fun containsSession(address: SignalProtocolAddress): Boolean =
        db.queryOne("SELECT record FROM signal_sessions WHERE address = ?", address.key()) { SessionRecord(it.blob("record")) }
            ?.hasSenderChain() == true

    override fun deleteSession(address: SignalProtocolAddress) = db.exec("DELETE FROM signal_sessions WHERE address = ?", address.key())

    override fun deleteAllSessions(name: String) = db.exec("DELETE FROM signal_sessions WHERE address LIKE ?", "$name.%")

    // Sender keys (group chats, Phase 3)

    override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) =
        db.exec("INSERT OR REPLACE INTO signal_sender_keys (address, distribution_id, record) VALUES (?, ?, ?)", sender.key(), distributionId.toString(), record.serialize())

    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? =
        db.queryOne("SELECT record FROM signal_sender_keys WHERE address = ? AND distribution_id = ?", sender.key(), distributionId.toString()) {
            SenderKeyRecord(it.blob("record"))
        }

    private fun SignalProtocolAddress.key() = "$name.$deviceId"
}
