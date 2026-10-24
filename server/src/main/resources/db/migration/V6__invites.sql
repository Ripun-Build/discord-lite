-- Phase 5: invite links and bans.

-- A link like discordlite://invite/<code>. It stops working after [expires_at] or once it has
-- been used [max_uses] times (either can be "never").
CREATE TABLE server_invites (
    code       text        PRIMARY KEY CHECK (code ~ '^[A-Za-z0-9]{8}$'),
    server_id  uuid        NOT NULL REFERENCES servers (id) ON DELETE CASCADE,
    creator_id uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    max_uses   int         CHECK (max_uses IS NULL OR max_uses > 0),
    uses       int         NOT NULL DEFAULT 0,
    expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX server_invites_server_idx ON server_invites (server_id);

-- Banned people can't come back by any link (or be added) until unbanned.
CREATE TABLE server_bans (
    server_id  uuid        NOT NULL REFERENCES servers (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    banned_by  uuid        REFERENCES users (id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (server_id, user_id)
);

-- New "Create invites" power (bit 64) is on for everyone by default, like Discord.
UPDATE server_roles SET permissions = permissions | 64 WHERE is_default;
