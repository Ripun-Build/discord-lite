package com.discordlite.app.data.remote

import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.Part
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
    @GET("v1/keys/{userId}") suspend fun preKeyBundle(@Path("userId") userId: String, @Query("groupId") groupId: String? = null): PreKeyBundleResponse

    @POST("v1/messages") suspend fun sendMessage(@Body body: SendMessageBody): SendMessageResponse
    @GET("v1/messages") suspend fun pendingMessages(): List<Envelope>
    @POST("v1/messages/ack") suspend fun ack(@Body body: AckBody): Response<Unit>

    @GET("v1/groups") suspend fun groups(): List<GroupResponse>
    @POST("v1/groups") suspend fun createGroup(@Body body: CreateGroupBody): GroupResponse
    @GET("v1/groups/{groupId}") suspend fun group(@Path("groupId") groupId: String): GroupResponse
    @PATCH("v1/groups/{groupId}") suspend fun renameGroup(@Path("groupId") groupId: String, @Body body: RenameGroupBody): GroupResponse
    @POST("v1/groups/{groupId}/members") suspend fun addGroupMembers(@Path("groupId") groupId: String, @Body body: AddMembersBody): GroupResponse
    /** Your own id = leave. */
    @DELETE("v1/groups/{groupId}/members/{userId}") suspend fun removeGroupMember(@Path("groupId") groupId: String, @Path("userId") userId: String): Response<Unit>
    @POST("v1/groups/{groupId}/messages") suspend fun sendGroupMessage(@Path("groupId") groupId: String, @Body body: SendGroupMessageBody): SendMessageResponse

    @GET("v1/servers") suspend fun servers(): List<ServerSummaryResponse>
    @POST("v1/servers") suspend fun createServer(@Body body: CreateServerBody): ServerResponse
    @GET("v1/servers/{serverId}") suspend fun server(@Path("serverId") serverId: String): ServerResponse
    @PATCH("v1/servers/{serverId}") suspend fun renameServer(@Path("serverId") serverId: String, @Body body: RenameServerBody): ServerResponse
    @DELETE("v1/servers/{serverId}") suspend fun deleteServer(@Path("serverId") serverId: String): Response<Unit>
    @GET("v1/servers/{serverId}/members") suspend fun serverMembers(@Path("serverId") serverId: String): List<ServerMemberResponse>
    @POST("v1/servers/{serverId}/members") suspend fun addServerMembers(@Path("serverId") serverId: String, @Body body: AddMembersBody): ServerResponse
    /** Your own id = leave. */
    @DELETE("v1/servers/{serverId}/members/{userId}") suspend fun removeServerMember(@Path("serverId") serverId: String, @Path("userId") userId: String): Response<Unit>
    @PUT("v1/servers/{serverId}/members/{userId}/roles") suspend fun setMemberRoles(@Path("serverId") serverId: String, @Path("userId") userId: String, @Body body: RoleIdsBody): Response<Unit>
    @POST("v1/servers/{serverId}/roles") suspend fun createRole(@Path("serverId") serverId: String, @Body body: RoleBody): ServerResponse
    @PATCH("v1/servers/{serverId}/roles/{roleId}") suspend fun updateRole(@Path("serverId") serverId: String, @Path("roleId") roleId: String, @Body body: RoleBody): ServerResponse
    @DELETE("v1/servers/{serverId}/roles/{roleId}") suspend fun deleteRole(@Path("serverId") serverId: String, @Path("roleId") roleId: String): ServerResponse
    @POST("v1/servers/{serverId}/channels") suspend fun createChannel(@Path("serverId") serverId: String, @Body body: CreateChannelBody): ServerResponse
    @POST("v1/servers/{serverId}/categories") suspend fun createCategory(@Path("serverId") serverId: String, @Body body: CategoryBody): ServerResponse
    @PATCH("v1/servers/{serverId}/categories/{categoryId}") suspend fun renameCategory(@Path("serverId") serverId: String, @Path("categoryId") categoryId: String, @Body body: CategoryBody): ServerResponse
    @DELETE("v1/servers/{serverId}/categories/{categoryId}") suspend fun deleteCategory(@Path("serverId") serverId: String, @Path("categoryId") categoryId: String): ServerResponse
    @PATCH("v1/channels/{channelId}") suspend fun updateChannel(@Path("channelId") channelId: String, @Body body: UpdateChannelBody): ServerResponse
    @DELETE("v1/channels/{channelId}") suspend fun deleteChannel(@Path("channelId") channelId: String): ServerResponse
    @GET("v1/channels/{channelId}/messages") suspend fun channelMessages(@Path("channelId") channelId: String, @Query("before") before: Long? = null, @Query("limit") limit: Int = 50): List<ChannelMessageResponse>
    @POST("v1/channels/{channelId}/messages") suspend fun sendChannelMessage(@Path("channelId") channelId: String, @Body body: SendChannelMessageBody): ChannelMessageResponse
    @DELETE("v1/channels/{channelId}/messages/{messageId}") suspend fun deleteChannelMessage(@Path("channelId") channelId: String, @Path("messageId") messageId: Long): Response<Unit>
    @POST("v1/channels/{channelId}/read") suspend fun markChannelRead(@Path("channelId") channelId: String, @Body body: ReadBody): Response<Unit>
    @POST("v1/servers/{serverId}/invites") suspend fun createInvite(@Path("serverId") serverId: String, @Body body: CreateInviteBody): InviteResponse
    @GET("v1/servers/{serverId}/invites") suspend fun invites(@Path("serverId") serverId: String): List<InviteResponse>
    @GET("v1/invites/{code}") suspend fun invitePreview(@Path("code") code: String): InvitePreviewResponse
    @POST("v1/invites/{code}/join") suspend fun joinInvite(@Path("code") code: String): ServerResponse
    @DELETE("v1/invites/{code}") suspend fun deleteInvite(@Path("code") code: String): Response<Unit>
    @GET("v1/servers/{serverId}/bans") suspend fun bans(@Path("serverId") serverId: String): List<BanResponse>
    @PUT("v1/servers/{serverId}/bans/{userId}") suspend fun ban(@Path("serverId") serverId: String, @Path("userId") userId: String): Response<Unit>
    @DELETE("v1/servers/{serverId}/bans/{userId}") suspend fun unban(@Path("serverId") serverId: String, @Path("userId") userId: String): Response<Unit>

    @GET("v1/stories") suspend fun stories(): List<StoryAuthorResponse>
    @GET("v1/stories/archive") suspend fun storyArchive(): List<StoryResponse>
    @Multipart @POST("v1/stories") suspend fun postStory(@Part image: MultipartBody.Part, @Part("caption") caption: RequestBody?): StoryResponse
    @GET("v1/stories/{storyId}/media") suspend fun storyMedia(@Path("storyId") storyId: String): ResponseBody
    @POST("v1/stories/{storyId}/view") suspend fun viewStory(@Path("storyId") storyId: String): Response<Unit>
    @GET("v1/stories/{storyId}/viewers") suspend fun storyViewers(@Path("storyId") storyId: String): List<StoryViewerResponse>
    @DELETE("v1/stories/{storyId}") suspend fun deleteStory(@Path("storyId") storyId: String): Response<Unit>

    @GET("v1/users/{userId}/profile") suspend fun userProfile(@Path("userId") userId: String): UserProfileResponse
    @GET("v1/users/{userId}/followers") suspend fun followers(@Path("userId") userId: String): List<FollowUserResponse>
    @GET("v1/users/{userId}/following") suspend fun following(@Path("userId") userId: String): List<FollowUserResponse>
    @PUT("v1/users/{userId}/follow") suspend fun follow(@Path("userId") userId: String): FollowResponse
    @DELETE("v1/users/{userId}/follow") suspend fun unfollow(@Path("userId") userId: String): Response<Unit>
    @DELETE("v1/users/{userId}/follower") suspend fun removeFollower(@Path("userId") userId: String): Response<Unit>
    @GET("v1/follows/requests") suspend fun followRequests(): List<FollowUserResponse>
    @POST("v1/follows/requests/{userId}/accept") suspend fun acceptFollow(@Path("userId") userId: String): Response<Unit>
    @DELETE("v1/follows/requests/{userId}") suspend fun declineFollow(@Path("userId") userId: String): Response<Unit>

    @GET("v1/posts/feed") suspend fun postFeed(@Query("before") before: String? = null): List<PostResponse>
    @GET("v1/posts/saved") suspend fun savedPosts(@Query("before") before: String? = null): List<PostResponse>
    @GET("v1/users/{userId}/posts") suspend fun userPosts(@Path("userId") userId: String, @Query("before") before: String? = null): List<PostResponse>
    @GET("v1/posts/games") suspend fun games(): List<String>
    @Multipart @POST("v1/posts") suspend fun createPost(@Part images: List<MultipartBody.Part>, @Part("caption") caption: RequestBody?, @Part("game") game: RequestBody?): PostResponse
    @GET("v1/posts/{postId}") suspend fun post(@Path("postId") postId: String): PostResponse
    @DELETE("v1/posts/{postId}") suspend fun deletePost(@Path("postId") postId: String): Response<Unit>
    @GET("v1/posts/{postId}/media/{position}") suspend fun postMedia(@Path("postId") postId: String, @Path("position") position: Int): ResponseBody
    @PUT("v1/posts/{postId}/like") suspend fun likePost(@Path("postId") postId: String): LikeResponse
    @DELETE("v1/posts/{postId}/like") suspend fun unlikePost(@Path("postId") postId: String): LikeResponse
    @PUT("v1/posts/{postId}/save") suspend fun savePost(@Path("postId") postId: String): Response<Unit>
    @DELETE("v1/posts/{postId}/save") suspend fun unsavePost(@Path("postId") postId: String): Response<Unit>
    @GET("v1/posts/{postId}/comments") suspend fun comments(@Path("postId") postId: String): List<CommentResponse>
    @POST("v1/posts/{postId}/comments") suspend fun addComment(@Path("postId") postId: String, @Body body: NewCommentBody): CommentResponse
    @DELETE("v1/posts/{postId}/comments/{commentId}") suspend fun deleteComment(@Path("postId") postId: String, @Path("commentId") commentId: String): Response<Unit>

    @GET("v1/mentions") suspend fun mentions(): List<MentionResponse>
    @POST("v1/mentions/ack") suspend fun ackMentions(@Body body: MentionAckBody): Response<Unit>

    @PUT("v1/push/token") suspend fun savePushToken(@Body body: PushTokenBody): Response<Unit>
    @DELETE("v1/push/token") suspend fun deletePushToken(): Response<Unit>
}

/** Used only by the token refresher, on a client without the auth interceptor (so no loops). */
interface RefreshApi {
    @POST("v1/auth/refresh") fun refresh(@Body body: RefreshBody): Call<SessionTokens>
}
