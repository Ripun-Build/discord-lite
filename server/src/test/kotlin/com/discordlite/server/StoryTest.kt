package com.discordlite.server

import com.discordlite.server.db.exec
import com.discordlite.server.stories.StoryAuthorDto
import com.discordlite.server.stories.StoryDto
import com.discordlite.server.stories.StoryViewerDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoryTest {

    @Test
    fun `a story is re-saved as a clean JPEG and only friends can see it`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val alice = http.newUser(app)
        val bob = http.newUser(app)
        val stranger = http.newUser(app)
        http.befriend(alice, bob)

        // A big PNG goes in; a JPEG no wider than 1920 px comes out.
        val posted = http.postStory(alice, png(3000, 1500), caption = "  gg last night  ")
        assertEquals(HttpStatusCode.Created, posted.status, posted.bodyAsText())
        val story = posted.body<StoryDto>()
        assertEquals("gg last night", story.caption)
        assertEquals(1920 to 960, story.width to story.height)
        assertEquals(story.createdAt + 24 * 3600_000L, story.expiresAt)

        val photo = http.get("/v1/stories/${story.id}/media") { bearerAuth(bob.token) }
        assertEquals(HttpStatusCode.OK, photo.status)
        val bytes = photo.readRawBytes()
        assertEquals(0xFF.toByte() to 0xD8.toByte(), bytes[0] to bytes[1]) // JPEG
        assertEquals(1920, ImageIO.read(bytes.inputStream()).width)

        val bobsFeed = http.feed(bob)
        assertEquals(listOf(alice.id), bobsFeed.map { it.userId })
        assertFalse(bobsFeed.single().allSeen)
        assertTrue(http.feed(stranger).isEmpty())
        assertEquals(HttpStatusCode.NotFound, http.get("/v1/stories/${story.id}/media") { bearerAuth(stranger.token) }.status)

        // Your own story comes first in your feed.
        http.postStory(bob, png(400, 600))
        assertEquals(listOf(bob.id, alice.id), http.feed(bob).map { it.userId })
    }

    @Test
    fun `location and camera data never survive, even if the phone didn't strip them`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val alice = http.newUser(app)
        val bob = http.newUser(app)
        http.befriend(alice, bob)

        val secret = "GPS 26.1445N 91.7362E Pixel 6"
        val upload = jpegWithMetadata(secret)
        assertTrue(upload.containsText(secret))
        assertEquals(1200, ImageIO.read(upload.inputStream()).width) // still a valid photo

        val story = http.postStory(alice, upload).body<StoryDto>()
        val photo = http.get("/v1/stories/${story.id}/media") { bearerAuth(bob.token) }.readRawBytes()

        assertEquals(1200, ImageIO.read(photo.inputStream()).width)
        assertFalse(photo.containsText(secret))
        assertFalse(photo.containsText("Exif"))
        assertFalse((0 until photo.size - 1).any { photo[it] == 0xFF.toByte() && photo[it + 1] == 0xE1.toByte() }) // no EXIF/XMP block
    }

    @Test
    fun `anything that isn't a real picture is refused`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val alice = http.newUser(app)
        val garbage = http.postStory(alice, "<?php echo 'not an image'; ?>".toByteArray())
        assertEquals(HttpStatusCode.BadRequest, garbage.status)
        assertTrue(garbage.bodyAsText().contains("invalid_image"))
        assertEquals(HttpStatusCode.BadRequest, http.postStory(alice, png(10, 10), caption = "x".repeat(201)).status)
        assertEquals(0, app.media.size)
    }

    @Test
    fun `friends' views are counted and only the author sees who`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val alice = http.newUser(app)
        val bob = http.newUser(app)
        val carol = http.newUser(app)
        http.befriend(alice, bob)
        http.befriend(alice, carol)
        val story = http.postStory(alice, png(800, 800)).body<StoryDto>()

        http.post("/v1/stories/${story.id}/view") { bearerAuth(bob.token) }
        http.post("/v1/stories/${story.id}/view") { bearerAuth(bob.token) } // twice counts once
        http.post("/v1/stories/${story.id}/view") { bearerAuth(alice.token) } // her own doesn't count
        assertTrue(http.feed(bob).single().allSeen)
        assertFalse(http.feed(carol).single().allSeen)

        val mine = http.feed(alice).single().stories.single()
        assertEquals(1, mine.viewCount)
        assertNull(http.feed(bob).single().stories.single().viewCount) // others never see counts
        val viewers = http.get("/v1/stories/${story.id}/viewers") { bearerAuth(alice.token) }.body<List<StoryViewerDto>>()
        assertEquals(listOf(bob.id), viewers.map { it.userId })
        assertEquals(HttpStatusCode.NotFound, http.get("/v1/stories/${story.id}/viewers") { bearerAuth(bob.token) }.status)
    }

    @Test
    fun `after 24 hours a story leaves friends' feeds but stays in the author's archive`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val alice = http.newUser(app)
        val bob = http.newUser(app)
        http.befriend(alice, bob)
        val story = http.postStory(alice, png(600, 900), caption = "old one").body<StoryDto>()
        app.deps.db.tx { exec("UPDATE stories SET created_at = now() - interval '25 hours' WHERE id = ?", UUID.fromString(story.id)) }

        assertTrue(http.feed(bob).isEmpty())
        assertEquals(HttpStatusCode.NotFound, http.get("/v1/stories/${story.id}/media") { bearerAuth(bob.token) }.status)
        assertEquals(HttpStatusCode.NotFound, http.post("/v1/stories/${story.id}/view") { bearerAuth(bob.token) }.status)
        assertTrue(http.feed(alice).isEmpty())

        val archive = http.get("/v1/stories/archive") { bearerAuth(alice.token) }.body<List<StoryDto>>()
        assertEquals(listOf("old one"), archive.map { it.caption })
        assertEquals(HttpStatusCode.OK, http.get("/v1/stories/${story.id}/media") { bearerAuth(alice.token) }.status)
        assertTrue(http.get("/v1/stories/archive") { bearerAuth(bob.token) }.body<List<StoryDto>>().isEmpty())
    }

    @Test
    fun `deleting removes the photo itself, and unfriending or blocking hides stories`() = testApplication {
        val app = startApp()
        val http = jsonClient()
        val alice = http.newUser(app)
        val bob = http.newUser(app)
        val carol = http.newUser(app)
        http.befriend(alice, bob)
        http.befriend(alice, carol)
        val story = http.postStory(alice, png(500, 500)).body<StoryDto>()
        assertEquals(1, app.media.size)

        assertEquals(HttpStatusCode.NotFound, http.delete("/v1/stories/${story.id}") { bearerAuth(bob.token) }.status)
        http.delete("/v1/friends/${alice.id}") { bearerAuth(bob.token) }
        assertTrue(http.feed(bob).isEmpty())
        http.put("/v1/blocks/${alice.id}") { bearerAuth(carol.token) }
        assertTrue(http.feed(carol).isEmpty())

        assertEquals(HttpStatusCode.NoContent, http.delete("/v1/stories/${story.id}") { bearerAuth(alice.token) }.status)
        assertEquals(0, app.media.size)
        assertTrue(http.get("/v1/stories/archive") { bearerAuth(alice.token) }.body<List<StoryDto>>().isEmpty())
    }

    // --- helpers ---

    private fun png(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().apply {
            color = Color(88, 101, 242)
            fillRect(0, 0, width, height)
            dispose()
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    /** A real JPEG with an EXIF block and a comment block carrying [secret], as a phone camera might write. */
    private fun jpegWithMetadata(secret: String): ByteArray {
        val image = BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = Color(44, 170, 90)
            fillRect(0, 0, 1200, 800)
            dispose()
        }
        val jpeg = ByteArrayOutputStream().also { ImageIO.write(image, "jpeg", it) }.toByteArray()
        fun segment(marker: Int, payload: ByteArray): ByteArray {
            val length = payload.size + 2
            return byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + payload
        }
        val exif = "Exif\u0000\u0000".toByteArray() + secret.toByteArray()
        val start = jpeg.copyOfRange(0, 2) // the JPEG start marker; metadata blocks go right after it
        return start + segment(0xE1, exif) + segment(0xFE, secret.toByteArray()) + jpeg.copyOfRange(2, jpeg.size)
    }

    private fun ByteArray.containsText(text: String) = String(this, Charsets.ISO_8859_1).contains(text)

    private suspend fun HttpClient.postStory(user: TestUser, image: ByteArray, caption: String? = null): HttpResponse =
        submitFormWithBinaryData(
            url = "/v1/stories",
            formData = formData {
                append("image", image, Headers.build {
                    append(HttpHeaders.ContentType, "image/png")
                    append(HttpHeaders.ContentDisposition, "filename=\"photo.png\"")
                })
                if (caption != null) append("caption", caption)
            },
        ) { bearerAuth(user.token) }

    private suspend fun HttpClient.feed(user: TestUser): List<StoryAuthorDto> = get("/v1/stories") { bearerAuth(user.token) }.body()
}
