package com.discordlite.app.data.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.util.Base64
import androidx.core.content.edit
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.SecureRandom

/**
 * The on-device database: Signal keys and sessions, conversations and message history.
 *
 * Encrypted with SQLCipher (AES-256). Its passphrase is random, generated on first use, and stored
 * only in wrapped form — wrapped by a key that lives in the Android Keystore — so the database file
 * is unreadable if copied off the phone.
 */
class LocalDatabase(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var helper: SupportSQLiteOpenHelper? = null

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

    val db: SupportSQLiteDatabase
        @Synchronized get() = (helper ?: open().also { helper = it }).writableDatabase

    /** Deletes everything stored on this device for the signed-in account. */
    @Synchronized
    fun wipe() {
        helper?.close()
        helper = null
        context.deleteDatabase(DB_NAME)
        prefs.edit { remove(KEY_PASSPHRASE) }
    }

    private fun open(): SupportSQLiteOpenHelper {
        System.loadLibrary("sqlcipher")
        val factory = SupportOpenHelperFactory(passphrase())
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(DB_NAME)
            .callback(Schema)
            .build()
        return factory.create(config)
    }

    private fun passphrase(): ByteArray {
        prefs.getString(KEY_PASSPHRASE, null)?.let { wrapped ->
            runCatching { return aead.decrypt(Base64.decode(wrapped, Base64.NO_WRAP), AD) }
            // The Keystore key is gone (e.g. data restored onto another phone): start fresh.
            context.deleteDatabase(DB_NAME)
        }
        val fresh = ByteArray(32).also(SecureRandom()::nextBytes)
        prefs.edit { putString(KEY_PASSPHRASE, Base64.encodeToString(aead.encrypt(fresh, AD), Base64.NO_WRAP)) }
        return fresh
    }

    private object Schema : SupportSQLiteOpenHelper.Callback(VERSION) {
        override fun onCreate(db: SupportSQLiteDatabase) {
            listOf(
                "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
                // Signal Protocol state. Private keys live only here.
                "CREATE TABLE signal_local (id INTEGER PRIMARY KEY CHECK (id = 1), identity_key_pair BLOB NOT NULL, registration_id INTEGER NOT NULL)",
                "CREATE TABLE signal_identities (address TEXT PRIMARY KEY, identity_key BLOB NOT NULL, first_seen INTEGER NOT NULL)",
                "CREATE TABLE signal_prekeys (id INTEGER PRIMARY KEY, record BLOB NOT NULL)",
                "CREATE TABLE signal_signed_prekeys (id INTEGER PRIMARY KEY, record BLOB NOT NULL)",
                "CREATE TABLE signal_kyber_prekeys (id INTEGER PRIMARY KEY, record BLOB NOT NULL, last_resort INTEGER NOT NULL DEFAULT 0)",
                "CREATE TABLE signal_kyber_base_keys (kyber_id INTEGER NOT NULL, signed_id INTEGER NOT NULL, base_key BLOB NOT NULL, PRIMARY KEY (kyber_id, signed_id, base_key))",
                "CREATE TABLE signal_sessions (address TEXT PRIMARY KEY, record BLOB NOT NULL)",
                "CREATE TABLE signal_sender_keys (address TEXT NOT NULL, distribution_id TEXT NOT NULL, record BLOB NOT NULL, PRIMARY KEY (address, distribution_id))",
                // Chats.
                """
                CREATE TABLE conversations (
                    peer_id TEXT PRIMARY KEY,
                    display_name TEXT NOT NULL,
                    username TEXT NOT NULL,
                    last_message TEXT NOT NULL DEFAULT '',
                    last_at INTEGER NOT NULL DEFAULT 0,
                    unread INTEGER NOT NULL DEFAULT 0
                )
                """,
                """
                CREATE TABLE messages (
                    local_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    peer_id TEXT NOT NULL,
                    client_id TEXT NOT NULL,
                    from_me INTEGER NOT NULL,
                    kind TEXT NOT NULL DEFAULT 'text',
                    body TEXT NOT NULL,
                    sent_at INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    UNIQUE (peer_id, client_id, from_me)
                )
                """,
                "CREATE INDEX messages_peer_idx ON messages (peer_id, sent_at)",
            ).forEach { db.execSQL(it.trimIndent()) }
            onUpgrade(db, 1, VERSION)
        }

        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) GROUPS.forEach { db.execSQL(it.trimIndent()) }
        }

        /**
         * Version 2: group DMs. A group's chat reuses `conversations`/`messages` with the group id
         * as `peer_id`; `sender_id` says who wrote each incoming group message.
         */
        private val GROUPS = listOf(
            "ALTER TABLE conversations ADD COLUMN is_group INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE messages ADD COLUMN sender_id TEXT",
            // active = 0 once you've left or been removed (the history stays).
            "CREATE TABLE groups (id TEXT PRIMARY KEY, name TEXT, owner_id TEXT NOT NULL, epoch INTEGER NOT NULL, active INTEGER NOT NULL DEFAULT 1)",
            """
            CREATE TABLE group_members (
                group_id TEXT NOT NULL,
                user_id TEXT NOT NULL,
                username TEXT NOT NULL,
                display_name TEXT NOT NULL,
                position INTEGER NOT NULL,
                PRIMARY KEY (group_id, user_id)
            )
            """,
            // Our own sender key for each group, and which members we've already shared it with.
            "CREATE TABLE group_my_keys (group_id TEXT PRIMARY KEY, distribution_id TEXT NOT NULL)",
            "CREATE TABLE group_key_shared (group_id TEXT NOT NULL, distribution_id TEXT NOT NULL, user_id TEXT NOT NULL, PRIMARY KEY (group_id, distribution_id, user_id))",
            // Who has received / read each of our group messages.
            "CREATE TABLE group_receipts (group_id TEXT NOT NULL, client_id TEXT NOT NULL, user_id TEXT NOT NULL, state TEXT NOT NULL, PRIMARY KEY (group_id, client_id, user_id))",
        )
    }

    private companion object {
        const val VERSION = 2
        const val DB_NAME = "discordlite.db"
        const val PREFS = "local_db"
        const val KEY_PASSPHRASE = "passphrase"
        const val KEYSET_NAME = "db_keyset"
        const val KEYSET_PREFS = "db_keyset_prefs"
        const val MASTER_KEY_URI = "android-keystore://discord_lite_db_key"
        val AD = "discord-lite-db".toByteArray()
    }
}

// Small helpers so queries read like SQL.

fun SupportSQLiteDatabase.exec(sql: String, vararg args: Any?) = execSQL(sql, args)

fun <T> SupportSQLiteDatabase.queryList(sql: String, vararg args: Any?, map: (Cursor) -> T): List<T> =
    query(sql, args).use { c -> buildList { while (c.moveToNext()) add(map(c)) } }

fun <T> SupportSQLiteDatabase.queryOne(sql: String, vararg args: Any?, map: (Cursor) -> T): T? =
    queryList(sql, *args, map = map).firstOrNull()

fun Cursor.str(column: String): String = getString(getColumnIndexOrThrow(column))
fun Cursor.int(column: String): Int = getInt(getColumnIndexOrThrow(column))
fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))
fun Cursor.blob(column: String): ByteArray = getBlob(getColumnIndexOrThrow(column))

fun SupportSQLiteDatabase.metaGet(key: String): String? = queryOne("SELECT value FROM meta WHERE key = ?", key) { it.str("value") }
fun SupportSQLiteDatabase.metaSet(key: String, value: String) =
    insert("meta", android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE, ContentValues().apply { put("key", key); put("value", value) })
