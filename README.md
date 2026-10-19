# discord-lite

A privacy-first, end-to-end encrypted real-time chat app for Android.

- **Client:** Kotlin, Jetpack Compose, Room + SQLCipher, libsignal
- **Backend:** Kotlin + Ktor, PostgreSQL, Redis, S3-compatible blob storage
- **Security:** Signal Protocol (PQXDH + Double Ratchet), sealed sender, no phone numbers, no contact upload, minimal server metadata

Status: Phase 0 — project setup.

## Repo layout

```
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
