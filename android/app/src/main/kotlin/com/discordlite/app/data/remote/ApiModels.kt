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

/** [groupId] is set for group messages and for 1:1 envelopes sent inside a group (sender keys, receipts). */
@Serializable data class Envelope(val id: Long, val senderId: String, val type: Int, val body: String, val sentAt: Long, val groupId: String? = null)
@Serializable data class SendMessageBody(
    val recipientId: String,
    val type: Int,
    val body: String,
    val clientId: String,
    val registrationId: Int? = null,
    val groupId: String? = null,
)
@Serializable data class SendMessageResponse(val id: Long, val sentAt: Long)
@Serializable data class AckBody(val ids: List<Long>)
@Serializable data class GatewayFrame(
    val t: String,
    val envelope: Envelope? = null,
    val ids: List<Long>? = null,
    val to: String? = null,
    val groupId: String? = null,
    val group: GroupResponse? = null,
    val serverId: String? = null,
    val channelId: String? = null,
    val channelMessage: ChannelMessageResponse? = null,
    val messageId: Long? = null,
    val userId: String? = null,
)
@Serializable data class PushTokenBody(val token: String)

// Phase 3: group DMs. The server knows who's in a group; what's said is end-to-end encrypted.

@Serializable data class GroupMemberResponse(val id: String, val username: String, val displayName: String)

/** [epoch] goes up whenever someone joins or leaves. */
@Serializable
data class GroupResponse(
    val id: String,
    val name: String? = null,
    val ownerId: String,
    val epoch: Int,
    val members: List<GroupMemberResponse>,
    val createdAt: Long,
)

@Serializable data class CreateGroupBody(val name: String?, val memberIds: List<String>)
@Serializable data class RenameGroupBody(val name: String?)
@Serializable data class AddMembersBody(val userIds: List<String>)
@Serializable data class SendGroupMessageBody(val type: Int, val body: String, val clientId: String, val epoch: Int)

// Phase 4: servers, channels and roles. Server channels are NOT end-to-end encrypted.

object Permissions {
    const val MANAGE_SERVER = 1L
    const val MANAGE_CHANNELS = 2L
    const val MANAGE_ROLES = 4L
    const val KICK_MEMBERS = 8L
    const val MANAGE_MESSAGES = 16L
    const val ADD_MEMBERS = 32L
}

@Serializable data class CategoryResponse(val id: String, val name: String, val position: Int)
@Serializable data class ChannelResponse(val id: String, val name: String, val categoryId: String? = null, val position: Int, val unread: Boolean = false, val mentions: Int = 0)
@Serializable data class RoleResponse(val id: String, val name: String, val color: Int, val permissions: Long, val position: Int, val isDefault: Boolean, val memberCount: Int)
@Serializable data class ServerSummaryResponse(val id: String, val name: String, val ownerId: String, val unread: Boolean, val mentions: Int)

@Serializable
data class ServerResponse(
    val id: String,
    val name: String,
    val ownerId: String,
    val myPermissions: Long,
    val categories: List<CategoryResponse>,
    val channels: List<ChannelResponse>,
    val roles: List<RoleResponse>,
    val memberCount: Int,
    val createdAt: Long,
) {
    fun can(permission: Long) = myPermissions and permission != 0L
}

@Serializable data class ServerMemberResponse(val id: String, val username: String, val displayName: String, val roleIds: List<String>, val joinedAt: Long)

@Serializable
data class ChannelMessageResponse(
    val id: Long,
    val serverId: String,
    val channelId: String,
    val authorId: String,
    val authorUsername: String,
    val authorDisplayName: String,
    val body: String,
    val createdAt: Long,
    val mentionIds: List<String> = emptyList(),
    val clientId: String? = null,
)

@Serializable data class MentionResponse(val message: ChannelMessageResponse, val serverName: String, val channelName: String)

@Serializable data class CreateServerBody(val name: String, val template: String, val memberIds: List<String>)
@Serializable data class RenameServerBody(val name: String)
@Serializable data class CreateChannelBody(val name: String, val categoryId: String? = null)
@Serializable data class UpdateChannelBody(val name: String? = null, val categoryId: String? = null, val uncategorized: Boolean = false)
@Serializable data class CategoryBody(val name: String)
@Serializable data class RoleBody(val name: String, val color: Int, val permissions: Long)
@Serializable data class RoleIdsBody(val roleIds: List<String>)
@Serializable data class SendChannelMessageBody(val body: String, val clientId: String)
@Serializable data class ReadBody(val lastReadId: Long)
@Serializable data class MentionAckBody(val messageIds: List<Long>)
