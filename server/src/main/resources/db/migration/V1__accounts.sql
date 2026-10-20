-- Phase 1: accounts, email codes, sessions, friends and blocks.

CREATE EXTENSION IF NOT EXISTS citext;

CREATE TABLE users (
    id             uuid        PRIMARY KEY,
    email          citext      NOT NULL UNIQUE,
    email_verified boolean     NOT NULL DEFAULT false,
    username       text        NOT NULL UNIQUE CHECK (username ~ '^[a-z0-9._]{3,20}$'),
    display_name   text        NOT NULL CHECK (char_length(display_name) BETWEEN 1 AND 32),
    bio            text        NOT NULL DEFAULT '' CHECK (char_length(bio) <= 160),
    password_hash  text        NOT NULL,
    dm_policy      text        NOT NULL DEFAULT 'FRIENDS_AND_SERVERS'
                               CHECK (dm_policy IN ('EVERYONE', 'FRIENDS_AND_SERVERS', 'FRIENDS', 'NOBODY')),
    created_at     timestamptz NOT NULL DEFAULT now()
);

-- One-time codes for email verification and password reset. Only an HMAC of the code is stored.
CREATE TABLE email_codes (
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    purpose    text        NOT NULL CHECK (purpose IN ('VERIFY_EMAIL', 'RESET_PASSWORD')),
    code_hash  bytea       NOT NULL,
    expires_at timestamptz NOT NULL,
    attempts   int         NOT NULL DEFAULT 0,
    sent_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, purpose)
);

-- Refresh tokens are stored as SHA-256 hashes and rotated on every use. A token presented twice
-- means it was stolen, so its whole family (one sign-in session) is revoked.
CREATE TABLE refresh_tokens (
    token_hash bytea       PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id  uuid        NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX refresh_tokens_family_idx ON refresh_tokens (family_id);
CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id);

CREATE TABLE friendships (
    requester_id uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    addressee_id uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status       text        NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED')),
    created_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (requester_id, addressee_id),
    CHECK (requester_id <> addressee_id)
);
-- At most one row per pair, whichever direction it was requested in.
CREATE UNIQUE INDEX friendships_pair_idx
    ON friendships (LEAST(requester_id, addressee_id), GREATEST(requester_id, addressee_id));
CREATE INDEX friendships_addressee_idx ON friendships (addressee_id);

CREATE TABLE blocks (
    blocker_id uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    blocked_id uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (blocker_id, blocked_id),
    CHECK (blocker_id <> blocked_id)
);
CREATE INDEX blocks_blocked_idx ON blocks (blocked_id);
