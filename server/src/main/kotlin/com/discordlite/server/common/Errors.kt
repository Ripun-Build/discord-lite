package com.discordlite.server.common

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

/** An error the client is meant to see. [code] is a stable, machine-readable string. */
class ApiException(val status: HttpStatusCode, val code: String) : RuntimeException(code)

fun badRequest(code: String) = ApiException(HttpStatusCode.BadRequest, code)
fun unauthorized(code: String = "unauthorized") = ApiException(HttpStatusCode.Unauthorized, code)
fun forbidden(code: String) = ApiException(HttpStatusCode.Forbidden, code)
fun notFound(code: String) = ApiException(HttpStatusCode.NotFound, code)
fun conflict(code: String) = ApiException(HttpStatusCode.Conflict, code)
fun tooMany(code: String) = ApiException(HttpStatusCode.TooManyRequests, code)

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class StatusResponse(val status: String)
