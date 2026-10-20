package com.discordlite.app.data.remote

import com.discordlite.app.data.session.TokenStore
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A failed API call. [code] is the server's error string ("invalid_code"), or "network". */
class ApiError(val status: Int, val code: String) : Exception(code)

private val errorJson = Json { ignoreUnknownKeys = true }

/** Runs an API call and turns HTTP/network failures into [ApiError]. */
suspend fun <T> apiCall(block: suspend () -> T): T = try {
    block()
} catch (e: HttpException) {
    val code = runCatching {
        errorJson.decodeFromString(ErrorBody.serializer(), e.response()?.errorBody()?.string().orEmpty()).error
    }.getOrDefault("http_${e.code()}")
    throw ApiError(e.code(), code)
} catch (e: IOException) {
    throw ApiError(0, "network")
}

/** For calls returning `Response<Unit>` (204 No Content). */
suspend fun apiCallUnit(block: suspend () -> retrofit2.Response<Unit>) = apiCall {
    val response = block()
    if (!response.isSuccessful) throw HttpException(response)
}

object ApiClient {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun create(baseUrl: String, store: TokenStore): DiscordLiteApi {
        val converter = json.asConverterFactory("application/json".toMediaType())
        val plain = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        val refreshApi = Retrofit.Builder().baseUrl(baseUrl).client(plain).addConverterFactory(converter).build()
            .create(RefreshApi::class.java)

        val authed = plain.newBuilder()
            .addInterceptor(AuthInterceptor(store))
            .authenticator(TokenRefresher(store, refreshApi))
            .build()
        return Retrofit.Builder().baseUrl(baseUrl).client(authed).addConverterFactory(converter).build()
            .create(DiscordLiteApi::class.java)
    }
}

/** Attaches the access token to every request except the sign-in endpoints. */
private class AuthInterceptor(private val store: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = store.session.value?.accessToken
        if (token == null || request.url.encodedPath.startsWith("/v1/auth/")) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
    }
}

/**
 * On a 401, swaps the refresh token for a new pair once and retries. If several requests fail at
 * the same time only the first refreshes; the rest reuse its result. If the server refuses the
 * refresh token, the session is cleared and the app returns to sign-in.
 */
private class TokenRefresher(private val store: TokenStore, private val api: RefreshApi) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        val sent = response.request.header("Authorization")?.removePrefix("Bearer ") ?: return null
        if (response.priorResponse != null) return null // already retried once
        synchronized(this) {
            val current = store.session.value ?: return null
            if (current.accessToken != sent) return response.request.withToken(current.accessToken)
            val refreshed = try {
                api.refresh(RefreshBody(current.refreshToken)).execute()
            } catch (e: IOException) {
                return null
            }
            val tokens = refreshed.body()
            if (!refreshed.isSuccessful || tokens == null) {
                if (refreshed.code() == 401) store.clear(becauseExpired = true)
                return null
            }
            store.save(tokens)
            return response.request.withToken(tokens.accessToken)
        }
    }

    private fun Request.withToken(token: String) = newBuilder().header("Authorization", "Bearer $token").build()
}
