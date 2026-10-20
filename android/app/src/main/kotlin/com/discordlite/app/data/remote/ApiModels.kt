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
