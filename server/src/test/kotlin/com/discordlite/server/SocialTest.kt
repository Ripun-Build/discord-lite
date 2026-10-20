package com.discordlite.server

import com.discordlite.server.social.FriendsResponse
import com.discordlite.server.social.PublicUser
import com.discordlite.server.social.Relationship
import com.discordlite.server.users.DmPolicy
import com.discordlite.server.users.Me
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SocialTest {

    @Test
    fun `search finds users by username prefix, never yourself, never emails`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val alice = http.newUser(app)
        val bob = http.newUser(app)

        val response = http.get("/v1/users/search?q=@${bob.username.take(6)}") { bearerAuth(alice.token) }
        val results = response.body<List<PublicUser>>()
        assertTrue(results.any { it.id == bob.id && it.relationship == Relationship.NONE })
        assertFalse(response.bodyAsText().contains("@example.com"))

        val self = http.get("/v1/users/search?q=${alice.username}") { bearerAuth(alice.token) }.body<List<PublicUser>>()
        assertTrue(self.none { it.id == alice.id })
    }

    @Test
    fun `friend request, accept, then unfriend`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val alice = http.newUser(app)
        val bob = http.newUser(app)

        val sent = http.postJson("/v1/friends/requests", mapOf("userId" to bob.id), alice.token)
        assertTrue(sent.bodyAsText().contains("OUTGOING"))
        assertEquals(listOf(alice.id), http.friendsOf(bob).incoming.map { it.id })
        assertEquals(listOf(bob.id), http.friendsOf(alice).outgoing.map { it.id })

        val accepted = http.post("/v1/friends/requests/${alice.id}/accept") { bearerAuth(bob.token) }
        assertEquals(HttpStatusCode.OK, accepted.status)
        assertEquals(listOf(bob.id), http.friendsOf(alice).friends.map { it.id })
        assertEquals(listOf(alice.id), http.friendsOf(bob).friends.map { it.id })

        assertEquals(HttpStatusCode.NoContent, http.delete("/v1/friends/${bob.id}") { bearerAuth(alice.token) }.status)
        assertTrue(http.friendsOf(bob).friends.isEmpty())
    }

    @Test
    fun `asking someone who already asked you makes you friends`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val alice = http.newUser(app)
        val bob = http.newUser(app)
        http.postJson("/v1/friends/requests", mapOf("userId" to bob.id), alice.token)
        val back = http.postJson("/v1/friends/requests", mapOf("userId" to alice.id), bob.token)
        assertTrue(back.bodyAsText().contains("FRIEND"))
        assertEquals(listOf(alice.id), http.friendsOf(bob).friends.map { it.id })
    }

    @Test
    fun `declining or cancelling a request removes it`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val alice = http.newUser(app)
        val bob = http.newUser(app)
        http.postJson("/v1/friends/requests", mapOf("userId" to bob.id), alice.token)
        assertEquals(HttpStatusCode.NoContent, http.delete("/v1/friends/requests/${alice.id}") { bearerAuth(bob.token) }.status)
        val f = http.friendsOf(alice)
        assertTrue(f.outgoing.isEmpty() && f.friends.isEmpty())
        assertEquals(HttpStatusCode.NotFound, http.post("/v1/friends/requests/${alice.id}/accept") { bearerAuth(bob.token) }.status)
    }

    @Test
    fun `blocking ends the friendship and makes you invisible to each other`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val alice = http.newUser(app)
        val bob = http.newUser(app)
        http.postJson("/v1/friends/requests", mapOf("userId" to bob.id), alice.token)
        http.post("/v1/friends/requests/${alice.id}/accept") { bearerAuth(bob.token) }

        assertEquals(HttpStatusCode.NoContent, http.put("/v1/blocks/${bob.id}") { bearerAuth(alice.token) }.status)
        assertTrue(http.friendsOf(alice).friends.isEmpty())
        assertTrue(http.friendsOf(bob).friends.isEmpty())

        // Neither can find the other, and Bob's request fails exactly like a missing account.
        assertTrue(http.search(alice, bob.username).none { it.id == bob.id })
        assertTrue(http.search(bob, alice.username).none { it.id == alice.id })
        val again = http.postJson("/v1/friends/requests", mapOf("userId" to alice.id), bob.token)
        val missing = http.postJson("/v1/friends/requests", mapOf("userId" to UUID.randomUUID().toString()), bob.token)
        assertEquals(HttpStatusCode.NotFound, again.status)
        assertEquals(missing.bodyAsText(), again.bodyAsText())

        assertEquals(listOf(bob.id), http.get("/v1/blocks") { bearerAuth(alice.token) }.body<List<PublicUser>>().map { it.id })
        http.delete("/v1/blocks/${bob.id}") { bearerAuth(alice.token) }
        assertTrue(http.search(alice, bob.username).any { it.id == bob.id })
    }

    @Test
    fun `edit profile and DM setting`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val alice = http.newUser(app)
        val updated = http.patch("/v1/me") {
            bearerAuth(alice.token)
            contentType(ContentType.Application.Json)
            setBody(mapOf("displayName" to "Alice", "bio" to "gg", "dmPolicy" to "FRIENDS"))
        }.body<Me>()
        assertEquals("Alice", updated.displayName)
        assertEquals("gg", updated.bio)
        assertEquals(DmPolicy.FRIENDS, updated.dmPolicy)

        val tooLong = http.patch("/v1/me") {
            bearerAuth(alice.token)
            contentType(ContentType.Application.Json)
            setBody(mapOf("bio" to "x".repeat(161)))
        }
        assertEquals(HttpStatusCode.BadRequest, tooLong.status)
    }

    @Test
    fun `who can DM me is enforced`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val alice = http.newUser(app)
        val bob = http.newUser(app)
        val social = app.deps.social
        val a = UUID.fromString(alice.id)
        val b = UUID.fromString(bob.id)

        suspend fun setPolicy(policy: DmPolicy) = http.patch("/v1/me") {
            bearerAuth(bob.token)
            contentType(ContentType.Application.Json)
            setBody(mapOf("dmPolicy" to policy.name))
        }

        setPolicy(DmPolicy.EVERYONE)
        assertTrue(social.canStartDm(a, b))
        setPolicy(DmPolicy.FRIENDS)
        assertFalse(social.canStartDm(a, b))

        http.postJson("/v1/friends/requests", mapOf("userId" to bob.id), alice.token)
        http.post("/v1/friends/requests/${alice.id}/accept") { bearerAuth(bob.token) }
        assertTrue(social.canStartDm(a, b))

        setPolicy(DmPolicy.NOBODY)
        assertFalse(social.canStartDm(a, b))

        setPolicy(DmPolicy.EVERYONE)
        http.put("/v1/blocks/${alice.id}") { bearerAuth(bob.token) }
        assertFalse(social.canStartDm(a, b))
    }

    private suspend fun io.ktor.client.HttpClient.friendsOf(user: TestUser) =
        get("/v1/friends") { bearerAuth(user.token) }.body<FriendsResponse>()

    private suspend fun io.ktor.client.HttpClient.search(user: TestUser, q: String) =
        get("/v1/users/search?q=$q") { bearerAuth(user.token) }.body<List<PublicUser>>()
}
