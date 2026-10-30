package com.discordlite.server

import com.discordlite.server.follows.FollowResponse
import com.discordlite.server.follows.FollowState
import com.discordlite.server.follows.FollowUserDto
import com.discordlite.server.follows.UserProfileDto
import com.discordlite.server.posts.CommentDto
import com.discordlite.server.posts.LikeResponse
import com.discordlite.server.posts.NewCommentRequest
import com.discordlite.server.posts.PostDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PostTest {

    @Test
    fun `public posts are visible to anyone signed in, but the feed only shows people you follow`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val alice = http.newUser(app)
        val bob = http.newUser(app)

        val post = http.newPost(alice, 3, caption = "  ace clutch  ", game = " valorant ").body<PostDto>()
        assertEquals("ace clutch", post.caption)
        assertEquals("Valorant", post.game) // spelling of known games is tidied up
        assertEquals(3, post.media.size)
        assertEquals(3, app.media.size)

        assertEquals(post.id, http.get("/v1/posts/${post.id}") { bearerAuth(bob.token) }.body<PostDto>().id)
        assertEquals(HttpStatusCode.OK, http.get("/v1/posts/${post.id}/media/2") { bearerAuth(bob.token) }.status)
        assertEquals(HttpStatusCode.NotFound, http.get("/v1/posts/${post.id}/media/3") { bearerAuth(bob.token) }.status)
        assertTrue(http.feed(bob).isEmpty())
        assertEquals(listOf(post.id), http.feed(alice).map { it.id }) // your own posts are in your feed

        assertEquals(FollowState.FOLLOWING, http.follow(bob, alice))
        assertEquals(listOf(post.id), http.feed(bob).map { it.id })

        assertEquals(HttpStatusCode.NoContent, http.delete("/v1/users/${alice.id}/follow") { bearerAuth(bob.token) }.status)
        assertTrue(http.feed(bob).isEmpty())
    }

    @Test
    fun `any game name can be typed, within reason`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val alice = http.newUser(app)
        assertEquals("Hollow Knight", http.newPost(alice, 1, game = "Hollow   Knight").body<PostDto>().game)
        assertNull(http.newPost(alice, 1, game = "   ").body<PostDto>().game)
        assertEquals(HttpStatusCode.BadRequest, http.newPost(alice, 1, game = "x".repeat(41)).status)
        assertTrue(http.get("/v1/posts/games") { bearerAuth(alice.token) }.body<List<String>>().contains("Valorant"))
    }

    @Test
    fun `a post needs one to five real photos`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val alice = http.newUser(app)
        assertEquals(HttpStatusCode.BadRequest, http.newPost(alice, 0).status)
        assertEquals(HttpStatusCode.BadRequest, http.newPost(alice, 6).status)
        assertEquals(HttpStatusCode.BadRequest, http.newPost(alice, 1, caption = "x".repeat(501)).status)

        val mixed = http.submitPost(alice, listOf(png(), "<?php nope ?>".toByteArray()))
        assertEquals(HttpStatusCode.BadRequest, mixed.status)
        assertTrue(mixed.bodyAsText().contains("invalid_image"))
        assertEquals(0, app.media.size) // nothing is stored unless the whole post is good
        assertEquals(HttpStatusCode.Created, http.newPost(alice, 5).status)
    }

    @Test
    fun `location and camera data never survive in a post`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val alice = http.newUser(app)
        val secret = "GPS 26.1445N 91.7362E"
        val post = http.submitPost(alice, listOf(jpegWithExif(secret))).body<PostDto>()
        val photo = http.get("/v1/posts/${post.id}/media/0") { bearerAuth(alice.token) }.readRawBytes()
        assertFalse(String(photo, Charsets.ISO_8859_1).contains(secret))
        assertFalse(String(photo, Charsets.ISO_8859_1).contains("Exif"))
    }

    @Test
    fun `a private account shows nothing until the follow request is approved`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val alice = http.newUser(app)
        val bob = http.newUser(app)
        val carol = http.newUser(app)
        http.setPrivate(alice, true)
        val post = http.newPost(alice, 1).body<PostDto>()

        // A stranger sees the name and counts, but no posts, photos, likes or comments.
        val seen = http.profile(bob, alice)
        assertTrue(seen.isPrivate)
        assertFalse(seen.canSeePosts)
        assertEquals(1, seen.postCount)
        assertTrue(http.get("/v1/users/${alice.id}/posts") { bearerAuth(bob.token) }.body<List<PostDto>>().isEmpty())
        for (path in listOf("", "/media/0", "/comments")) {
            assertEquals(HttpStatusCode.NotFound, http.get("/v1/posts/${post.id}$path") { bearerAuth(bob.token) }.status, path)
        }
        assertEquals(HttpStatusCode.NotFound, http.put("/v1/posts/${post.id}/like") { bearerAuth(bob.token) }.status)
        assertEquals(HttpStatusCode.NotFound, http.put("/v1/posts/${post.id}/save") { bearerAuth(bob.token) }.status)
        assertEquals(HttpStatusCode.NotFound, http.comment(bob, post.id, "hi").status)
        assertEquals(HttpStatusCode.Forbidden, http.get("/v1/users/${alice.id}/followers") { bearerAuth(bob.token) }.status)

        // Asking isn't enough.
        assertEquals(FollowState.REQUESTED, http.follow(bob, alice))
        assertEquals(FollowState.REQUESTED, http.follow(carol, alice))
        assertTrue(http.feed(bob).isEmpty())
        assertEquals(0, http.profile(bob, alice).followerCount)
        assertEquals(2, http.profile(alice, alice).requestCount)
        assertNull(http.profile(bob, alice).requestCount) // nobody else sees how many are waiting

        val requests = http.get("/v1/follows/requests") { bearerAuth(alice.token) }.body<List<FollowUserDto>>()
        assertEquals(setOf(bob.id, carol.id), requests.map { it.id }.toSet())

        assertEquals(HttpStatusCode.NoContent, http.post("/v1/follows/requests/${bob.id}/accept") { bearerAuth(alice.token) }.status)
        assertEquals(HttpStatusCode.NoContent, http.delete("/v1/follows/requests/${carol.id}") { bearerAuth(alice.token) }.status)
        assertEquals(listOf(post.id), http.feed(bob).map { it.id })
        assertEquals(HttpStatusCode.OK, http.get("/v1/posts/${post.id}/media/0") { bearerAuth(bob.token) }.status)
        assertEquals(HttpStatusCode.NotFound, http.get("/v1/posts/${post.id}") { bearerAuth(carol.token) }.status)

        // Removing a follower locks them out again.
        assertEquals(HttpStatusCode.NoContent, http.delete("/v1/users/${bob.id}/follower") { bearerAuth(alice.token) }.status)
        assertEquals(HttpStatusCode.NotFound, http.get("/v1/posts/${post.id}") { bearerAuth(bob.token) }.status)
        assertTrue(http.feed(bob).isEmpty())
    }

    @Test
    fun `going public lets in everyone who was waiting`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val alice = http.newUser(app)
        val bob = http.newUser(app)
        http.setPrivate(alice, true)
        assertEquals(FollowState.REQUESTED, http.follow(bob, alice))
        http.setPrivate(alice, false)
        assertEquals(FollowState.FOLLOWING, http.profile(bob, alice).followState)
    }

    @Test
    fun `going private doesn't remove existing followers, but stops new ones`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val alice = http.newUser(app)
        val bob = http.newUser(app)
        val carol = http.newUser(app)
        http.follow(bob, alice)
        val post = http.newPost(alice, 1).body<PostDto>()
        http.setPrivate(alice, true)
        assertEquals(listOf(post.id), http.feed(bob).map { it.id })
        assertEquals(FollowState.REQUESTED, http.follow(carol, alice))
    }

    @Test
    fun `friends follow each other automatically, for as long as they are friends`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val alice = http.newUser(app)
        val bob = http.newUser(app)
        http.setPrivate(alice, true)
        val post = http.newPost(alice, 1).body<PostDto>()

        http.befriend(alice, bob)
        assertEquals(listOf(post.id), http.feed(bob).map { it.id }) // even on a private account
        assertEquals(FollowState.FOLLOWING, http.profile(alice, bob).followState)
        assertTrue(http.profile(bob, alice).followsYou)

        // It can't be undone from one side while you're friends.
        assertEquals(HttpStatusCode.Conflict, http.delete("/v1/users/${alice.id}/follow") { bearerAuth(bob.token) }.status)
        assertEquals(HttpStatusCode.Conflict, http.delete("/v1/users/${bob.id}/follower") { bearerAuth(alice.token) }.status)

        http.delete("/v1/friends/${alice.id}") { bearerAuth(bob.token) }
        assertEquals(FollowState.NONE, http.profile(bob, alice).followState)
        assertEquals(FollowState.NONE, http.profile(alice, bob).followState)
        assertEquals(HttpStatusCode.NotFound, http.get("/v1/posts/${post.id}") { bearerAuth(bob.token) }.status)
    }

    @Test
    fun `a friend's pending follow request turns into a real follow`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val alice = http.newUser(app)
        val bob = http.newUser(app)
        http.setPrivate(alice, true)
        http.follow(bob, alice)
        http.befriend(alice, bob)
        assertEquals(FollowState.FOLLOWING, http.profile(bob, alice).followState)
        assertEquals(0, http.profile(alice, alice).requestCount)
    }

    @Test
    fun `blocking hides everything both ways and clears follows`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val alice = http.newUser(app)
        val bob = http.newUser(app)
        val carol = http.newUser(app)
        http.follow(bob, alice)
        http.follow(alice, bob)
        val alicePost = http.newPost(alice, 1).body<PostDto>()
        val bobPost = http.newPost(bob, 1).body<PostDto>()
        http.comment(bob, alicePost.id, "from bob")
        http.comment(carol, alicePost.id, "from carol")

        http.put("/v1/blocks/${bob.id}") { bearerAuth(alice.token) }

        assertEquals(HttpStatusCode.NotFound, http.get("/v1/posts/${alicePost.id}") { bearerAuth(bob.token) }.status)
        assertEquals(HttpStatusCode.NotFound, http.get("/v1/posts/${bobPost.id}") { bearerAuth(alice.token) }.status)
        assertEquals(HttpStatusCode.NotFound, http.get("/v1/users/${alice.id}/profile") { bearerAuth(bob.token) }.status)
        assertEquals(HttpStatusCode.NotFound, http.put("/v1/users/${alice.id}/follow") { bearerAuth(bob.token) }.status)
        assertEquals(listOf(alicePost.id), http.feed(alice).map { it.id })
        assertTrue(http.feed(bob).none { it.id == alicePost.id })
        assertEquals(0, http.profile(alice, alice).followerCount)

        // Their comment on your post disappears for you.
        assertEquals(listOf("from carol"), http.comments(alice, alicePost.id).map { it.body })
        http.delete("/v1/blocks/${bob.id}") { bearerAuth(alice.token) }
        assertEquals(FollowState.NONE, http.profile(bob, alice).followState) // a follow doesn't come back by itself
    }

    @Test
    fun `likes are counted once and saves stay private`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val alice = http.newUser(app)
        val bob = http.newUser(app)
        val post = http.newPost(alice, 1).body<PostDto>()

        assertEquals(LikeResponse(true, 1), http.put("/v1/posts/${post.id}/like") { bearerAuth(bob.token) }.body())
        assertEquals(LikeResponse(true, 1), http.put("/v1/posts/${post.id}/like") { bearerAuth(bob.token) }.body())
        assertEquals(LikeResponse(true, 2), http.put("/v1/posts/${post.id}/like") { bearerAuth(alice.token) }.body())
        assertTrue(http.get("/v1/posts/${post.id}") { bearerAuth(bob.token) }.body<PostDto>().liked)
        assertEquals(LikeResponse(false, 1), http.delete("/v1/posts/${post.id}/like") { bearerAuth(bob.token) }.body())

        http.put("/v1/posts/${post.id}/save") { bearerAuth(bob.token) }
        assertEquals(listOf(post.id), http.saved(bob).map { it.id })
        assertTrue(http.saved(alice).isEmpty())
        assertFalse(http.get("/v1/posts/${post.id}") { bearerAuth(alice.token) }.body<PostDto>().saved)

        // A saved post you can't see any more drops out of your list.
        http.setPrivate(alice, true)
        assertTrue(http.saved(bob).isEmpty())
        http.setPrivate(alice, false)
        assertEquals(listOf(post.id), http.saved(bob).map { it.id })
        http.delete("/v1/posts/${post.id}/save") { bearerAuth(bob.token) }
        assertTrue(http.saved(bob).isEmpty())
    }

    @Test
    fun `comments have one level of replies, and the post's author can remove any of them`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val alice = http.newUser(app)
        val bob = http.newUser(app)
        val carol = http.newUser(app)
        val post = http.newPost(alice, 1).body<PostDto>()

        val top = http.comment(bob, post.id, "  nice shot  ").body<CommentDto>()
        assertEquals("nice shot", top.body)
        assertNull(top.parentId)
        val reply = http.comment(carol, post.id, "agreed", parentId = top.id).body<CommentDto>()
        assertEquals(top.id, reply.parentId)
        val replyToReply = http.comment(bob, post.id, "thanks", parentId = reply.id).body<CommentDto>()
        assertEquals(top.id, replyToReply.parentId) // stays in the same thread
        assertEquals(3, http.get("/v1/posts/${post.id}") { bearerAuth(alice.token) }.body<PostDto>().commentCount)

        // Bad input
        assertEquals(HttpStatusCode.BadRequest, http.comment(bob, post.id, "   ").status)
        assertEquals(HttpStatusCode.BadRequest, http.comment(bob, post.id, "x".repeat(501)).status)
        val other = http.newPost(alice, 1).body<PostDto>()
        assertEquals(HttpStatusCode.NotFound, http.comment(bob, other.id, "wrong post", parentId = top.id).status)

        // Who may delete
        assertEquals(listOf(false, true, false), http.comments(carol, post.id).map { it.canDelete }) // only their own
        assertEquals(HttpStatusCode.NotFound, http.delete("/v1/posts/${post.id}/comments/${top.id}") { bearerAuth(carol.token) }.status)
        assertEquals(HttpStatusCode.NoContent, http.delete("/v1/posts/${post.id}/comments/${reply.id}") { bearerAuth(carol.token) }.status)
        assertEquals(listOf(top.id, replyToReply.id), http.comments(alice, post.id).map { it.id })

        // Removing a comment takes its replies with it
        assertTrue(http.comments(alice, post.id).all { it.canDelete })
        assertEquals(HttpStatusCode.NoContent, http.delete("/v1/posts/${post.id}/comments/${top.id}") { bearerAuth(alice.token) }.status)
        assertTrue(http.comments(alice, post.id).isEmpty())
    }

    @Test
    fun `deleting a post removes its photos, and only the author can`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val alice = http.newUser(app)
        val bob = http.newUser(app)
        val post = http.newPost(alice, 4).body<PostDto>()
        http.comment(bob, post.id, "gg")
        http.put("/v1/posts/${post.id}/save") { bearerAuth(bob.token) }

        assertEquals(HttpStatusCode.NotFound, http.delete("/v1/posts/${post.id}") { bearerAuth(bob.token) }.status)
        assertEquals(4, app.media.size)
        assertEquals(HttpStatusCode.NoContent, http.delete("/v1/posts/${post.id}") { bearerAuth(alice.token) }.status)
        assertEquals(0, app.media.size)
        assertEquals(HttpStatusCode.NotFound, http.get("/v1/posts/${post.id}") { bearerAuth(alice.token) }.status)
        assertTrue(http.saved(bob).isEmpty())
    }

    @Test
    fun `feeds load a page at a time, newest first, with nothing skipped or repeated`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val alice = http.newUser(app)
        val ids = List(23) { http.newPost(alice, 1).body<PostDto>().id }.reversed()

        val first = http.feed(alice)
        assertEquals(ids.take(20), first.map { it.id })
        val second = http.get("/v1/posts/feed?before=${first.last().id}") { bearerAuth(alice.token) }.body<List<PostDto>>()
        assertEquals(ids.drop(20), second.map { it.id })
    }

    @Test
    fun `profiles count posts and followers, and lists show who you already follow`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val alice = http.newUser(app)
        val bob = http.newUser(app)
        val carol = http.newUser(app)
        http.newPost(alice, 1)
        http.follow(bob, alice)
        http.follow(carol, alice)
        http.follow(bob, carol)

        val profile = http.profile(bob, alice)
        assertEquals(listOf(1, 2, 0), listOf(profile.postCount, profile.followerCount, profile.followingCount))
        assertEquals(FollowState.FOLLOWING, profile.followState)
        assertFalse(profile.followsYou)

        val followers = http.get("/v1/users/${alice.id}/followers") { bearerAuth(bob.token) }.body<List<FollowUserDto>>()
        assertEquals(mapOf(carol.id to FollowState.FOLLOWING, bob.id to FollowState.NONE), followers.associate { it.id to it.followState })
        val following = http.get("/v1/users/${bob.id}/following") { bearerAuth(alice.token) }.body<List<FollowUserDto>>()
        assertEquals(setOf(alice.id, carol.id), following.map { it.id }.toSet())
        assertEquals(HttpStatusCode.BadRequest, http.put("/v1/users/${alice.id}/follow") { bearerAuth(alice.token) }.status)
    }

    // --- helpers ---

    private fun png(): ByteArray {
        val image = BufferedImage(400, 500, BufferedImage.TYPE_INT_RGB)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun jpegWithExif(secret: String): ByteArray {
        val image = BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = Color(44, 170, 90)
            fillRect(0, 0, 600, 400)
            dispose()
        }
        val jpeg = ByteArrayOutputStream().also { ImageIO.write(image, "jpeg", it) }.toByteArray()
        val payload = "Exif\u0000\u0000".toByteArray() + secret.toByteArray()
        val length = payload.size + 2
        val segment = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
        return jpeg.copyOfRange(0, 2) + segment + jpeg.copyOfRange(2, jpeg.size)
    }

    private suspend fun HttpClient.submitPost(user: TestUser, images: List<ByteArray>, caption: String? = null, game: String? = null): HttpResponse =
        submitFormWithBinaryData(
            url = "/v1/posts",
            formData = formData {
                images.forEach {
                    append("image", it, Headers.build {
                        append(HttpHeaders.ContentType, "image/png")
                        append(HttpHeaders.ContentDisposition, "filename=\"photo.png\"")
                    })
                }
                if (caption != null) append("caption", caption)
                if (game != null) append("game", game)
            },
        ) { bearerAuth(user.token) }

    private suspend fun HttpClient.newPost(user: TestUser, photos: Int, caption: String? = null, game: String? = null) =
        submitPost(user, List(photos) { png() }, caption, game)

    private suspend fun HttpClient.feed(user: TestUser): List<PostDto> = get("/v1/posts/feed") { bearerAuth(user.token) }.body()

    private suspend fun HttpClient.saved(user: TestUser): List<PostDto> = get("/v1/posts/saved") { bearerAuth(user.token) }.body()

    private suspend fun HttpClient.profile(viewer: TestUser, target: TestUser): UserProfileDto =
        get("/v1/users/${target.id}/profile") { bearerAuth(viewer.token) }.body()

    private suspend fun HttpClient.follow(follower: TestUser, target: TestUser): FollowState =
        put("/v1/users/${target.id}/follow") { bearerAuth(follower.token) }.body<FollowResponse>().state

    private suspend fun HttpClient.setPrivate(user: TestUser, value: Boolean) {
        patch("/v1/me") {
            bearerAuth(user.token)
            contentType(ContentType.Application.Json)
            setBody("""{"isPrivate":$value}""")
        }
    }

    private suspend fun HttpClient.comment(user: TestUser, postId: String, body: String, parentId: String? = null): HttpResponse =
        postJson("/v1/posts/$postId/comments", NewCommentRequest(body, parentId), user.token)

    private suspend fun HttpClient.comments(user: TestUser, postId: String): List<CommentDto> =
        get("/v1/posts/$postId/comments") { bearerAuth(user.token) }.body()
}
