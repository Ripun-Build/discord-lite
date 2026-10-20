package com.discordlite.app.data.session

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import com.discordlite.app.data.remote.SessionTokens
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Holds the signed-in session. Tokens are encrypted with AES-256-GCM (Google Tink) under a key that
 * lives in the Android Keystore, so a copied app-data folder is useless on another device.
 */
class TokenStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    private val _session = MutableStateFlow(load())
    val session: StateFlow<SessionTokens?> = _session.asStateFlow()

    /** Set when the server rejected our refresh token, so the UI can say "please sign in again". */
    var expired: Boolean = false
        private set

    @Synchronized
    fun save(tokens: SessionTokens) {
        val cipher = aead.encrypt(json.encodeToString(SessionTokens.serializer(), tokens).toByteArray(), ASSOCIATED_DATA)
        prefs.edit { putString(KEY_SESSION, Base64.encodeToString(cipher, Base64.NO_WRAP)) }
        expired = false
        _session.value = tokens
    }

    @Synchronized
    fun clear(becauseExpired: Boolean = false) {
        prefs.edit { remove(KEY_SESSION) }
        expired = becauseExpired
        _session.value = null
    }

    private fun load(): SessionTokens? {
        val stored = prefs.getString(KEY_SESSION, null) ?: return null
        return runCatching {
            val plain = aead.decrypt(Base64.decode(stored, Base64.NO_WRAP), ASSOCIATED_DATA)
            json.decodeFromString(SessionTokens.serializer(), String(plain))
        }.getOrElse {
            // Key gone (e.g. app data restored elsewhere): treat as signed out.
            prefs.edit { remove(KEY_SESSION) }
            null
        }
    }

    private companion object {
        const val PREFS = "session"
        const val KEY_SESSION = "tokens"
        const val KEYSET_NAME = "session_keyset"
        const val KEYSET_PREFS = "session_keyset_prefs"
        const val MASTER_KEY_URI = "android-keystore://discord_lite_session_key"
        val ASSOCIATED_DATA = "discord-lite-session".toByteArray()
    }
}
