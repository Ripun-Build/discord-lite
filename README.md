# discord-lite

A privacy-first, end-to-end encrypted real-time chat app for Android.

- **Client:** Kotlin, Jetpack Compose, Room + SQLCipher, libsignal
- **Backend:** Kotlin + Ktor, PostgreSQL, Redis, S3-compatible blob storage
- **Security:** Signal Protocol (PQXDH + Double Ratchet), sealed sender, no phone numbers, no contact upload, minimal server metadata

Status: Phase 0 — project setup.

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
docker compose up -d --wait # Postgres :5432, Redis :6379, S3 :8333 (localhost only)
cd server && ./gradlew run  # API on http://localhost:8080
curl localhost:8080/health
```

Run tests: `cd server && ./gradlew test`

Android app (open `android/` in Android Studio, or):

```bash
cd android && ./gradlew :app:installDebug   # needs a running emulator or connected device
```

The UI currently runs on sample data — sign-in accepts any email/password and any 6-digit code.
