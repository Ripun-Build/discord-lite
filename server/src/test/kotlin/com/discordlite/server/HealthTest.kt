package com.discordlite.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthTest {
    @Test
    fun `health returns ok`() = testApplication {
        application { module() }
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"status\":\"ok\""))
    }

    @Test
    fun `unknown route returns json 404`() = testApplication {
        application { module() }
        val response = client.get("/does-not-exist")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("{\"error\":\"not_found\"}", response.bodyAsText())
    }
}
