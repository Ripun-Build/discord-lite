-- Phase 2: push notifications. One Firebase token per account (one device per account).
-- Pushes carry no content — they only wake the app, which then fetches and decrypts.
CREATE TABLE push_tokens (
    user_id    uuid        PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    token      text        NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
