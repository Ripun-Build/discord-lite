# discord-lite

A privacy-first, end-to-end encrypted real-time chat app for Android.

- **Client:** Kotlin, Jetpack Compose, SQLCipher (encrypted local database), libsignal
- **Backend:** Kotlin + Ktor, PostgreSQL, Redis, S3-compatible blob storage
- **Security:** Signal Protocol (PQXDH + Double Ratchet) for DMs, no phone numbers, no contact upload, minimal server metadata (sealed sender planned)

Status: Phase 7 done — end-to-end encrypted 1:1 and group chats, Discord-style servers with text channels, roles and @mentions (server channels are not end-to-end encrypted), invite links with cards and bans, friends-only photo stories that last 24 hours, and gaming posts with followers (stories and posts are not end-to-end encrypted; photos are re-saved without location or camera data). Reels (Phase 8) are next.

Still not done, and coming soon:

- Unblocking: you can block someone in the app, but there's no screen to unblock them yet.
- Notifications for follows, likes and comments. For now, follow requests only show on your Profile.
- Reporting posts, comments and accounts. Accounts are public by default, so this matters.
- The Share profile button does nothing yet.

## Repo layout

```
android/              Android app (Kotlin, Jetpack Compose)
  core/designsystem/  Theme, spring motion tokens, glass, components
  app/                Screens, navigation, sample data (until the backend is wired up)
server/               Ktor backend (Kotlin, Gradle)
docker-compose.yml    Local Postgres, Redis and S3-compatible storage (SeaweedFS)
.github/workflows/    CI
```

## Local development

Requirements: JDK 25, Docker Desktop.

```bash
cp .env.example .env        # then replace every "change-me" value
docker compose up -d --wait # Postgres :5432, Redis :6379, S3 :8333, Mailpit :8025 (localhost only)
cd server && ./gradlew run  # API on http://localhost:8080 (reads secrets from ../.env)
curl localhost:8080/health
```

Emails (verification and password-reset codes) are caught by Mailpit — open http://localhost:8025 to read them. Nothing is sent to real inboxes in development.

Run tests: `cd server && ./gradlew test`

Android app (open `android/` in Android Studio, or):

```bash
cd android && ./gradlew :app:installDebug   # needs a running emulator or connected device
```

The emulator reaches the local server at `http://10.0.2.2:8080`. On a real phone on the same Wi-Fi, build with `./gradlew :app:installDebug -PapiBaseUrl=http://<your-mac-ip>:8080/`.

### Test friend: @echobot

With the server running, start a bot that replies to whatever you send it — encrypted end to end with real Signal keys, like a second phone:

```bash
cd server && ./gradlew echoBot
```

In the app: Friends → search `echobot` → Add (it accepts automatically) → Message. Add it to a group (New message → New group) and it answers there too. Add it to a server (Servers → Add friends) and @mention it in a channel to get a reply. DM it an invite link (`discordlite://invite/<code>`) and it joins that server.

Accounts, profiles, friends, 1:1 chats, group chats and servers are real. Stories and the feed still use sample data until their phases.
