package com.discordlite.server.users

import com.discordlite.server.db.queryOne
import com.discordlite.server.db.uuid
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/** Who may start a new DM with this user. Enforced on the server when DMs land in Phase 2. */
@Serializable
enum class DmPolicy { EVERYONE, FRIENDS_AND_SERVERS, FRIENDS, NOBODY }

data class UserRow(
    val id: UUID,
    val email: String,
    val emailVerified: Boolean,
    val username: String,
    val displayName: String,
    val bio: String,
    val passwordHash: String,
    val dmPolicy: DmPolicy,
)

/** The signed-in user's own profile. The only response that ever contains an email address. */
@Serializable
data class Me(
    val id: String,
    val email: String,
    val username: String,
    val displayName: String,
    val bio: String,
    val dmPolicy: DmPolicy,
)

fun UserRow.toMe() = Me(id.toString(), email, username, displayName, bio, dmPolicy)

object UserRules {
    val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$")
    val USERNAME = Regex("^[a-z0-9._]{3,20}$")
    const val DISPLAY_NAME_MAX = 32
    const val BIO_MAX = 160
}

private const val USER_COLUMNS = "id, email, email_verified, username, display_name, bio, password_hash, dm_policy"

private fun ResultSet.toUser() = UserRow(
    id = uuid("id"),
    email = getString("email"),
    emailVerified = getBoolean("email_verified"),
    username = getString("username"),
    displayName = getString("display_name"),
    bio = getString("bio"),
    passwordHash = getString("password_hash"),
    dmPolicy = DmPolicy.valueOf(getString("dm_policy")),
)

fun Connection.findUserByEmail(email: String): UserRow? =
    queryOne("SELECT $USER_COLUMNS FROM users WHERE email = ?", email) { it.toUser() }

fun Connection.findUserById(id: UUID): UserRow? =
    queryOne("SELECT $USER_COLUMNS FROM users WHERE id = ?", id) { it.toUser() }
