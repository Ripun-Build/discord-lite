package com.discordlite.app.data

import android.content.Context
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import com.discordlite.app.data.local.metaSet
import com.discordlite.app.data.local.metaGet
import com.discordlite.app.data.local.LocalDatabase
import com.discordlite.app.crypto.SignalStore
import com.discordlite.app.crypto.KeyManager
import com.discordlite.app.chat.GatewayClient
import com.discordlite.app.chat.ChatRepository
import androidx.compose.runtime.staticCompositionLocalOf
import com.discordlite.app.BuildConfig
import com.discordlite.app.data.remote.ApiClient
import com.discordlite.app.data.remote.DiscordLiteApi
import com.discordlite.app.data.remote.DmPolicy
import com.discordlite.app.data.remote.EmailBody
import com.discordlite.app.data.remote.FriendRequestBody
import com.discordlite.app.data.remote.Friends
import com.discordlite.app.data.remote.LoginBody
import com.discordlite.app.data.remote.Me
import com.discordlite.app.data.remote.PublicUser
import com.discordlite.app.data.remote.RefreshBody
import com.discordlite.app.data.remote.ResetBody
import com.discordlite.app.data.remote.SignUpBody
import com.discordlite.app.data.remote.UpdateMeBody
import com.discordlite.app.data.remote.VerifyBody
import com.discordlite.app.data.remote.apiCall
import com.discordlite.app.data.remote.apiCallUnit
import com.discordlite.app.data.session.TokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Sign-up, sign-in, and the signed-in user's own profile. */
class AccountRepository(private val api: DiscordLiteApi, private val store: TokenStore) {
    private val _me = MutableStateFlow<Me?>(null)
    val me: StateFlow<Me?> = _me.asStateFlow()

    val isSignedIn: Boolean get() = store.session.value != null
    val session get() = store.session
    val sessionExpired: Boolean get() = store.expired

    suspend fun signUp(email: String, username: String, password: String) {
        apiCall { api.signUp(SignUpBody(email.trim(), username.trim().lowercase(), password)) }
    }

    suspend fun verifyEmail(email: String, code: String) {
        val response = apiCall { api.verify(VerifyBody(email.trim(), code)) }
        store.save(response.session)
        _me.value = response.user
    }

    suspend fun resendCode(email: String) {
        apiCall { api.resend(EmailBody(email.trim())) }
    }

    suspend fun signIn(email: String, password: String) {
        val response = apiCall { api.login(LoginBody(email.trim(), password)) }
        store.save(response.session)
        _me.value = response.user
    }

    suspend fun requestPasswordReset(email: String) {
        apiCall { api.forgotPassword(EmailBody(email.trim())) }
    }

    /** Resets the password, then signs in with it. */
    suspend fun resetPassword(email: String, code: String, newPassword: String) {
        apiCallUnit { api.resetPassword(ResetBody(email.trim(), code, newPassword)) }
        signIn(email, newPassword)
    }

    suspend fun loadMe(): Me = apiCall { api.me() }.also { _me.value = it }

    suspend fun updateProfile(displayName: String? = null, bio: String? = null, dmPolicy: DmPolicy? = null): Me =
        apiCall { api.updateMe(UpdateMeBody(displayName, bio, dmPolicy)) }.also { _me.value = it }

    suspend fun signOut() {
        store.session.value?.let { tokens -> runCatching { apiCallUnit { api.logout(RefreshBody(tokens.refreshToken)) } } }
        store.clear()
        _me.value = null
    }
}

/** Friends, requests, blocks and user search. Keeps the latest friends list for the UI. */
class SocialRepository(private val api: DiscordLiteApi) {
    private val _friends = MutableStateFlow(Friends())
    val friends: StateFlow<Friends> = _friends.asStateFlow()

    suspend fun refresh(): Friends = apiCall { api.friends() }.also { _friends.value = it }

    suspend fun search(query: String): List<PublicUser> = apiCall { api.search(query) }

    suspend fun sendRequest(userId: String) {
        apiCall { api.sendFriendRequest(FriendRequestBody(userId)) }
        refresh()
    }

    suspend fun accept(userId: String) {
        apiCall { api.acceptFriendRequest(userId) }
        refresh()
    }

    /** Declines an incoming request or cancels one you sent. */
    suspend fun removeRequest(userId: String) {
        apiCallUnit { api.removeFriendRequest(userId) }
        refresh()
    }

    suspend fun unfriend(userId: String) {
        apiCallUnit { api.unfriend(userId) }
        refresh()
    }

    suspend fun block(userId: String) {
        apiCallUnit { api.block(userId) }
        refresh()
    }

    fun clear() {
        _friends.value = Friends()
    }
}

/** App-wide singletons. Created once in [com.discordlite.app.DiscordLiteApp]. */
class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val tokenStore = TokenStore(context)
    private val clients = ApiClient.create(BuildConfig.API_BASE_URL, tokenStore)
    private val api = clients.api
    val account = AccountRepository(api, tokenStore)
    val settings = AppSettings(context)
    val social = SocialRepository(api)

    private val local = LocalDatabase(context)
    private val signalStore: SignalStore = SignalStore(local) { address -> chat.onIdentityChanged(address.substringBefore('.')) }
    private val keys = KeyManager(api, signalStore, local)
    val chat: ChatRepository = ChatRepository(api, signalStore, local, social, settings, scope) { account.me.value?.id ?: local.db.metaGet(OWNER) }
    val gateway = GatewayClient(clients.http, BuildConfig.API_BASE_URL, scope, chat::receive, chat::retryPending)
        .also { gateway -> chat.sendEphemeral = gateway::sendEphemeral }

    private val bootLock = Mutex()

    /**
     * Gets a signed-in device ready to chat: makes sure the local data belongs to this account,
     * publishes Signal keys if needed, and opens the live connection. Safe to call repeatedly.
     */
    suspend fun startSession() = bootLock.withLock {
        if (!account.isSignedIn) return@withLock
        val me = account.me.value ?: runCatching { account.loadMe() }.getOrNull() ?: return@withLock
        withContext(Dispatchers.IO) {
            val owner = local.db.metaGet(OWNER)
            if (owner != null && owner != me.id) local.wipe() // a different account used this phone before
            local.db.metaSet(OWNER, me.id)
        }
        runCatching { keys.ensurePublished() }
        gateway.start()
    }

    /** Signing out removes this account's keys and messages from the phone. */
    suspend fun endSession() {
        gateway.stop()
        account.signOut()
        social.clear()
        withContext(Dispatchers.IO) { local.wipe() }
    }

    private companion object {
        const val OWNER = "owner"
    }
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
