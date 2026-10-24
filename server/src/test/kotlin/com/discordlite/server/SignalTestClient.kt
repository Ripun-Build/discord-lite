package com.discordlite.server

import com.discordlite.server.messaging.Envelope
import com.discordlite.server.messaging.KyberPreKeyDto
import com.discordlite.server.messaging.OneTimePreKeyDto
import com.discordlite.server.messaging.PreKeyBundleDto
import com.discordlite.server.messaging.SignedPreKeyDto
import com.discordlite.server.messaging.UploadKeysRequest
import com.discordlite.server.messaging.b64
import com.discordlite.server.messaging.unb64
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.groups.GroupCipher
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import org.signal.libsignal.protocol.util.KeyHelper
import java.util.UUID

/**
 * A real Signal Protocol client living in the test, so tests prove the whole path works:
 * keys published → bundle fetched → session built → encrypted → relayed → decrypted.
 */
class SignalTestClient(val userId: String) {
    private val identity = IdentityKeyPair.generate()
    private val registrationId = KeyHelper.generateRegistrationId(false)
    val store = InMemorySignalProtocolStore(identity, registrationId)
    private val self = SignalProtocolAddress(userId, DEVICE)

    fun keyUpload(oneTimeCount: Int = 5, kyberCount: Int = 3): UploadKeysRequest {
        val now = System.currentTimeMillis()
        val signedPair = ECKeyPair.generate()
        val signed = SignedPreKeyRecord(1, now, signedPair, identity.privateKey.calculateSignature(signedPair.publicKey.serialize()))
        store.storeSignedPreKey(signed.id, signed)

        fun kyber(id: Int): KyberPreKeyRecord {
            val pair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            return KyberPreKeyRecord(id, now, pair, identity.privateKey.calculateSignature(pair.publicKey.serialize()))
                .also { store.storeKyberPreKey(id, it) }
        }
        val lastResort = kyber(1)
        val kybers = (2 until 2 + kyberCount).map(::kyber)
        val oneTimes = (1..oneTimeCount).map { id -> PreKeyRecord(id, ECKeyPair.generate()).also { store.storePreKey(id, it) } }

        return UploadKeysRequest(
            identityKey = b64(identity.publicKey.serialize()),
            registrationId = registrationId,
            signedPreKey = SignedPreKeyDto(signed.id, b64(signedPair.publicKey.serialize()), b64(signed.signature)),
            lastResortKyberPreKey = lastResort.toDto(),
            kyberPreKeys = kybers.map { it.toDto() },
            oneTimePreKeys = oneTimes.map { OneTimePreKeyDto(it.id, b64(it.keyPair.publicKey.serialize())) },
        )
    }

    /** Builds a session from a bundle the server handed out (libsignal verifies the signatures). */
    fun startSession(bundle: PreKeyBundleDto) {
        val oneTime = bundle.oneTimePreKey
        val preKeyBundle = PreKeyBundle(
            bundle.registrationId,
            DEVICE,
            oneTime?.keyId ?: PreKeyBundle.NULL_PRE_KEY_ID,
            oneTime?.let { ECPublicKey(unb64(it.publicKey)) },
            bundle.signedPreKey.keyId,
            ECPublicKey(unb64(bundle.signedPreKey.publicKey)),
            unb64(bundle.signedPreKey.signature),
            IdentityKey(unb64(bundle.identityKey)),
            bundle.kyberPreKey.keyId,
            KEMPublicKey(unb64(bundle.kyberPreKey.publicKey)),
            unb64(bundle.kyberPreKey.signature),
        )
        // Note the argument order: SessionBuilder takes (remote, local); SessionCipher takes (local, remote).
        SessionBuilder(store, address(bundle.userId), self).process(preKeyBundle)
    }

    fun encrypt(to: String, text: String): CiphertextMessage =
        SessionCipher(store, self, address(to)).encrypt(text.toByteArray())

    fun decrypt(envelope: Envelope): String {
        val cipher = SessionCipher(store, self, address(envelope.senderId))
        val bytes = unb64(envelope.body)
        val plain = when (envelope.type) {
            CiphertextMessage.PREKEY_TYPE -> cipher.decrypt(PreKeySignalMessage(bytes))
            CiphertextMessage.WHISPER_TYPE -> cipher.decrypt(SignalMessage(bytes))
            else -> error("unexpected type ${envelope.type}")
        }
        return String(plain)
    }

    /**
     * Group messaging with sender keys: make our sender key for [distributionId] and get the
     * message that shares it (sent to each member over our 1:1 sessions).
     */
    fun senderKeyFor(distributionId: UUID): SenderKeyDistributionMessage =
        GroupSessionBuilder(store).create(self, distributionId)

    /** Stores a member's sender key that arrived inside a 1:1 message. */
    fun acceptSenderKey(from: String, distribution: ByteArray) =
        GroupSessionBuilder(store).process(address(from), SenderKeyDistributionMessage(distribution))

    /** Encrypts once for the whole group (a type-7 sender-key message). */
    fun encryptForGroup(distributionId: UUID, text: String): CiphertextMessage =
        GroupCipher(store, self).encrypt(distributionId, text.toByteArray())

    fun decryptGroup(envelope: Envelope): String {
        check(envelope.type == CiphertextMessage.SENDERKEY_TYPE) { "unexpected type ${envelope.type}" }
        return String(GroupCipher(store, address(envelope.senderId)).decrypt(unb64(envelope.body)))
    }

    /** Decrypts a 1:1 envelope to raw bytes (e.g. a sender key being shared). */
    fun decryptBytes(envelope: Envelope): ByteArray {
        val cipher = SessionCipher(store, self, address(envelope.senderId))
        val bytes = unb64(envelope.body)
        return when (envelope.type) {
            CiphertextMessage.PREKEY_TYPE -> cipher.decrypt(PreKeySignalMessage(bytes))
            CiphertextMessage.WHISPER_TYPE -> cipher.decrypt(SignalMessage(bytes))
            else -> error("unexpected type ${envelope.type}")
        }
    }

    fun encryptBytes(to: String, bytes: ByteArray): CiphertextMessage =
        SessionCipher(store, self, address(to)).encrypt(bytes)

    private fun address(user: String) = SignalProtocolAddress(user, DEVICE)

    private fun KyberPreKeyRecord.toDto() = KyberPreKeyDto(id, b64(keyPair.publicKey.serialize()), b64(signature))

    companion object {
        const val DEVICE = 1
    }
}
