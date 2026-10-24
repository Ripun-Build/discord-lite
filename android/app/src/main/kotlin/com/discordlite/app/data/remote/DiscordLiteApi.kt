package com.discordlite.app.data.remote

import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface DiscordLiteApi {
    @POST("v1/auth/signup") suspend fun signUp(@Body body: SignUpBody): StatusResponse
    @POST("v1/auth/verify") suspend fun verify(@Body body: VerifyBody): AuthResponse
    @POST("v1/auth/resend") suspend fun resend(@Body body: EmailBody): StatusResponse
    @POST("v1/auth/login") suspend fun login(@Body body: LoginBody): AuthResponse
    @POST("v1/auth/password/forgot") suspend fun forgotPassword(@Body body: EmailBody): StatusResponse
    @POST("v1/auth/password/reset") suspend fun resetPassword(@Body body: ResetBody): Response<Unit>
    @POST("v1/auth/logout") suspend fun logout(@Body body: RefreshBody): Response<Unit>

    @GET("v1/me") suspend fun me(): Me
    @PATCH("v1/me") suspend fun updateMe(@Body body: UpdateMeBody): Me

    @GET("v1/users/search") suspend fun search(@Query("q") query: String): List<PublicUser>
    @GET("v1/users/{userId}") suspend fun user(@Path("userId") userId: String): PublicUser

    @GET("v1/friends") suspend fun friends(): Friends
    @POST("v1/friends/requests") suspend fun sendFriendRequest(@Body body: FriendRequestBody): RelationshipResponse
    @POST("v1/friends/requests/{userId}/accept") suspend fun acceptFriendRequest(@Path("userId") userId: String): RelationshipResponse
    @DELETE("v1/friends/requests/{userId}") suspend fun removeFriendRequest(@Path("userId") userId: String): Response<Unit>
    @DELETE("v1/friends/{userId}") suspend fun unfriend(@Path("userId") userId: String): Response<Unit>

    @GET("v1/blocks") suspend fun blocked(): List<PublicUser>
    @PUT("v1/blocks/{userId}") suspend fun block(@Path("userId") userId: String): Response<Unit>
    @DELETE("v1/blocks/{userId}") suspend fun unblock(@Path("userId") userId: String): Response<Unit>

    @PUT("v1/keys") suspend fun uploadKeys(@Body body: UploadKeysBody): Response<Unit>
    @POST("v1/keys/topup") suspend fun topUpKeys(@Body body: TopUpKeysBody): Response<Unit>
    @GET("v1/keys/count") suspend fun keyCounts(): PreKeyCounts
    @GET("v1/keys/{userId}") suspend fun preKeyBundle(@Path("userId") userId: String): PreKeyBundleResponse

    @POST("v1/messages") suspend fun sendMessage(@Body body: SendMessageBody): SendMessageResponse
    @GET("v1/messages") suspend fun pendingMessages(): List<Envelope>
    @POST("v1/messages/ack") suspend fun ack(@Body body: AckBody): Response<Unit>

    @PUT("v1/push/token") suspend fun savePushToken(@Body body: PushTokenBody): Response<Unit>
    @DELETE("v1/push/token") suspend fun deletePushToken(): Response<Unit>
}

/** Used only by the token refresher, on a client without the auth interceptor (so no loops). */
interface RefreshApi {
    @POST("v1/auth/refresh") fun refresh(@Body body: RefreshBody): Call<SessionTokens>
}
