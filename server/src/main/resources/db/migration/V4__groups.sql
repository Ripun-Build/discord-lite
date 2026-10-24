-- Phase 3: encrypted group DMs (up to 25 people).
-- The server knows who is in a group (it has to, to deliver messages) but never what is said.
-- Messages are encrypted on the phone with Signal "sender keys"; the server fans the same
-- ciphertext out to every member.

CREATE TABLE groups (
    id         uuid        PRIMARY KEY,
    owner_id   uuid        NOT NULL REFERENCES users (id),
    -- Optional; without one the app shows the members' names.
    name       text        CHECK (name IS NULL OR char_length(name) BETWEEN 1 AND 100),
    -- Goes up by one whenever someone joins or leaves. A sender whose view of the group is out of
    -- date is told so (and refreshes, sharing or rotating its sender key) before anything is sent.
    epoch      int         NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE group_members (
    group_id  uuid        NOT NULL REFERENCES groups (id) ON DELETE CASCADE,
    user_id   uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    joined_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (group_id, user_id)
);
CREATE INDEX group_members_user_idx ON group_members (user_id);

-- One group message is queued once per recipient, so the same client id now repeats across
-- recipients. Envelopes that belong to a group say which one.
ALTER TABLE message_queue ADD COLUMN group_id uuid REFERENCES groups (id) ON DELETE CASCADE;
ALTER TABLE message_queue DROP CONSTRAINT message_queue_sender_id_client_id_key;
ALTER TABLE message_queue ADD CONSTRAINT message_queue_sender_client_recipient_key UNIQUE (sender_id, client_id, recipient_id);
