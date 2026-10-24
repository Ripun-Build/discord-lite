-- Phase 4: servers, channels and roles.
-- Server channels are NOT end-to-end encrypted (a deliberate choice: servers can be large and need
-- history for people who join later). Messages are stored as plain text on our server.

CREATE TABLE servers (
    id         uuid        PRIMARY KEY,
    owner_id   uuid        NOT NULL REFERENCES users (id),
    name       text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 100),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE server_members (
    server_id uuid        NOT NULL REFERENCES servers (id) ON DELETE CASCADE,
    user_id   uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    joined_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (server_id, user_id)
);
CREATE INDEX server_members_user_idx ON server_members (user_id);

-- Roles grant permissions (a bit set, see Permissions in Servers.kt). Every member has the
-- server's default "everyone" role implicitly; other roles are given out in member_roles.
CREATE TABLE server_roles (
    id          uuid    PRIMARY KEY,
    server_id   uuid    NOT NULL REFERENCES servers (id) ON DELETE CASCADE,
    name        text    NOT NULL CHECK (char_length(name) BETWEEN 1 AND 32),
    color       int     NOT NULL DEFAULT 0,
    permissions bigint  NOT NULL DEFAULT 0,
    position    int     NOT NULL DEFAULT 0,
    is_default  boolean NOT NULL DEFAULT false
);
CREATE INDEX server_roles_server_idx ON server_roles (server_id);
CREATE UNIQUE INDEX server_roles_default_idx ON server_roles (server_id) WHERE is_default;

CREATE TABLE member_roles (
    server_id uuid NOT NULL,
    user_id   uuid NOT NULL,
    role_id   uuid NOT NULL REFERENCES server_roles (id) ON DELETE CASCADE,
    PRIMARY KEY (server_id, user_id, role_id),
    FOREIGN KEY (server_id, user_id) REFERENCES server_members (server_id, user_id) ON DELETE CASCADE
);

CREATE TABLE channel_categories (
    id        uuid PRIMARY KEY,
    server_id uuid NOT NULL REFERENCES servers (id) ON DELETE CASCADE,
    name      text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 32),
    position  int  NOT NULL DEFAULT 0
);

-- Text channels. (Voice comes later.) Deleting a category keeps its channels, uncategorised.
CREATE TABLE channels (
    id          uuid        PRIMARY KEY,
    server_id   uuid        NOT NULL REFERENCES servers (id) ON DELETE CASCADE,
    category_id uuid        REFERENCES channel_categories (id) ON DELETE SET NULL,
    name        text        NOT NULL CHECK (name ~ '^[a-z0-9_-]{1,32}$'),
    position    int         NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX channels_server_idx ON channels (server_id);

CREATE TABLE channel_messages (
    id         bigserial   PRIMARY KEY,
    channel_id uuid        NOT NULL REFERENCES channels (id) ON DELETE CASCADE,
    author_id  uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    body       text        NOT NULL CHECK (char_length(body) BETWEEN 1 AND 4000),
    client_id  uuid        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (author_id, client_id)
);
CREATE INDEX channel_messages_channel_idx ON channel_messages (channel_id, id);

-- Who a message @mentions. [notified] flips once the person's phone has shown the notification.
CREATE TABLE message_mentions (
    message_id bigint  NOT NULL REFERENCES channel_messages (id) ON DELETE CASCADE,
    user_id    uuid    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    notified   boolean NOT NULL DEFAULT false,
    PRIMARY KEY (message_id, user_id)
);
CREATE INDEX message_mentions_user_idx ON message_mentions (user_id) WHERE NOT notified;

-- How far each member has read in each channel (for unread dots and mention badges).
CREATE TABLE channel_reads (
    user_id      uuid   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    channel_id   uuid   NOT NULL REFERENCES channels (id) ON DELETE CASCADE,
    last_read_id bigint NOT NULL,
    PRIMARY KEY (user_id, channel_id)
);
