package com.discordlite.app.data.remote

import kotlinx.serialization.Serializable

// Mirrors the server's JSON. Field names must match exactly.

@Serializable
data class SessionTokens(val accessToken: String, val refreshToken: String, val expiresIn: Long)

@Serializable
enum class DmPolicy { EVERYONE, FRIENDS_AND_SERVERS, FRIENDS, NOBODY }

@Serializable
data class Me(
    val id: String,
    val email: String,
    val username: String,
    val displayName: String,
    val bio: String,
    val dmPolicy: DmPolicy,
)

@Serializable
data class AuthResponse(val session: SessionTokens, val user: Me)

@Serializable
enum class Relationship { NONE, FRIEND, OUTGOING, INCOMING }

@Serializable
data class PublicUser(
    val id: String,
    val username: String,
    val displayName: String,
    val bio: String,
    val relationship: Relationship,
)

@Serializable
data class Friends(
    val friends: List<PublicUser> = emptyList(),
    val incoming: List<PublicUser> = emptyList(),
    val outgoing: List<PublicUser> = emptyList(),
)

@Serializable data class SignUpBody(val email: String, val username: String, val password: String, val displayName: String? = null)
@Serializable data class VerifyBody(val email: String, val code: String)
@Serializable data class EmailBody(val email: String)
@Serializable data class LoginBody(val email: String, val password: String)
@Serializable data class ResetBody(val email: String, val code: String, val newPassword: String)
@Serializable data class RefreshBody(val refreshToken: String)
@Serializable data class UpdateMeBody(val displayName: String? = null, val bio: String? = null, val dmPolicy: DmPolicy? = null)
@Serializable data class FriendRequestBody(val userId: String)
@Serializable data class RelationshipResponse(val relationship: Relationship)
@Serializable data class StatusResponse(val status: String)
@Serializable data class ErrorBody(val error: String)

// Phase 2: Signal keys and encrypted messages. Byte fields are base64.

@Serializable data class SignedPreKeyBody(val keyId: Int, val publicKey: String, val signature: String)
@Serializable data class KyberPreKeyBody(val keyId: Int, val publicKey: String, val signature: String)
@Serializable data class OneTimePreKeyBody(val keyId: Int, val publicKey: String)

@Serializable
data class UploadKeysBody(
    val identityKey: String,
    val registrationId: Int,
    val signedPreKey: SignedPreKeyBody,
    val lastResortKyberPreKey: KyberPreKeyBody,
    val kyberPreKeys: List<KyberPreKeyBody>,
    val oneTimePreKeys: List<OneTimePreKeyBody>,
)

@Serializable
data class TopUpKeysBody(
    val kyberPreKeys: List<KyberPreKeyBody> = emptyList(),
    val oneTimePreKeys: List<OneTimePreKeyBody> = emptyList(),
)

@Serializable data class PreKeyCounts(val oneTime: Int, val kyber: Int)

@Serializable
data class PreKeyBundleResponse(
    val userId: String,
    val registrationId: Int,
    val identityKey: String,
    val signedPreKey: SignedPreKeyBody,
    val kyberPreKey: KyberPreKeyBody,
    val oneTimePreKey: OneTimePreKeyBody? = null,
)

@Serializable data class Envelope(val id: Long, val senderId: String, val type: Int, val body: String, val sentAt: Long)
@Serializable data class SendMessageBody(val recipientId: String, val type: Int, val body: String, val clientId: String, val registrationId: Int? = null)
@Serializable data class SendMessageResponse(val id: Long, val sentAt: Long)
@Serializable data class AckBody(val ids: List<Long>)
@Serializable data class GatewayFrame(val t: String, val envelope: Envelope? = null, val ids: List<Long>? = null, val to: String? = null)
@Serializable data class PushTokenBody(val token: String)
