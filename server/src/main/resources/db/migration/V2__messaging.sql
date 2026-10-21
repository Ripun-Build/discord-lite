-- Phase 2: Signal Protocol public keys and the encrypted message queue.
-- The server only ever holds PUBLIC keys and ciphertext; it cannot read messages.

CREATE TABLE identity_keys (
    user_id         uuid        PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    identity_key    bytea       NOT NULL,
    registration_id int         NOT NULL,
    updated_at      timestamptz NOT NULL DEFAULT now()
);

-- One current signed pre-key per user (rotated by the client).
CREATE TABLE signed_prekeys (
    user_id    uuid        PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    key_id     int         NOT NULL,
    public_key bytea       NOT NULL,
    signature  bytea       NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- Post-quantum (Kyber-1024) pre-keys. One-time ones are deleted when handed out; the
-- "last resort" key is reused only when the one-time ones run out.
CREATE TABLE kyber_prekeys (
    user_id     uuid    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    key_id      int     NOT NULL,
    public_key  bytea   NOT NULL,
    signature   bytea   NOT NULL,
    last_resort boolean NOT NULL DEFAULT false,
    PRIMARY KEY (user_id, key_id)
);

-- Classic one-time pre-keys, each handed out exactly once.
CREATE TABLE one_time_prekeys (
    user_id    uuid  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    key_id     int   NOT NULL,
    public_key bytea NOT NULL,
    PRIMARY KEY (user_id, key_id)
);

-- Marks that two people have a DM. "Who can DM me" applies to starting one; an existing DM
-- keeps working (unless someone blocks).
CREATE TABLE dm_threads (
    user_low   uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    user_high  uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_low, user_high),
    CHECK (user_low < user_high)
);

-- Encrypted envelopes waiting for their recipient. Deleted as soon as the recipient acknowledges
-- them; anything undelivered for 30 days is dropped.
CREATE TABLE message_queue (
    id           bigserial   PRIMARY KEY,
    recipient_id uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    sender_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type         smallint    NOT NULL,
    body         bytea       NOT NULL,
    client_id    uuid        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (sender_id, client_id)
);
CREATE INDEX message_queue_recipient_idx ON message_queue (recipient_id, id);
CREATE INDEX message_queue_created_idx ON message_queue (created_at);
