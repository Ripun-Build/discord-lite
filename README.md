# discord-lite

A privacy-first, end-to-end encrypted real-time chat app for Android.

- **Client:** Kotlin, Jetpack Compose, Room + SQLCipher, libsignal
- **Backend:** Kotlin + Ktor, PostgreSQL, Redis, S3-compatible blob storage
- **Security:** Signal Protocol (PQXDH + Double Ratchet), sealed sender, no phone numbers, no contact upload, minimal server metadata

Status: Phase 1 — accounts and friends (email sign-up, verification codes, profiles, friend requests, blocking).

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

Accounts, profiles and friends are real. Chats, servers, stories and the feed still use sample data until their phases.
