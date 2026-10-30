-- Phase 7: gaming posts and followers. Not end-to-end encrypted. Accounts are public unless the
-- owner makes them private; then people have to ask to follow, and only followers see the posts.

ALTER TABLE users ADD COLUMN is_private boolean NOT NULL DEFAULT false;

CREATE TABLE follows (
    follower_id uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    followee_id uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status      text        NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED')),
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (follower_id, followee_id),
    CHECK (follower_id <> followee_id)
);
CREATE INDEX follows_followee_idx ON follows (followee_id, status);

-- Friends follow each other automatically, including everyone who is already friends.
INSERT INTO follows (follower_id, followee_id, status)
SELECT requester_id, addressee_id, 'ACCEPTED' FROM friendships WHERE status = 'ACCEPTED'
UNION
SELECT addressee_id, requester_id, 'ACCEPTED' FROM friendships WHERE status = 'ACCEPTED';

CREATE TABLE posts (
    id         uuid        PRIMARY KEY,
    author_id  uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    caption    text        CHECK (caption IS NULL OR char_length(caption) BETWEEN 1 AND 500),
    game       text        CHECK (game IS NULL OR char_length(game) BETWEEN 1 AND 40),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX posts_author_idx ON posts (author_id, created_at DESC);
CREATE INDEX posts_recent_idx ON posts (created_at DESC, id DESC);

-- The photos themselves live in the media store under [media_key].
CREATE TABLE post_media (
    post_id   uuid     NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    position  smallint NOT NULL CHECK (position BETWEEN 0 AND 4),
    media_key text     NOT NULL UNIQUE,
    width     int      NOT NULL,
    height    int      NOT NULL,
    PRIMARY KEY (post_id, position)
);

CREATE TABLE post_likes (
    post_id    uuid        NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (post_id, user_id)
);

-- Saved posts are private: nobody else, not even the author, can see who saved what.
CREATE TABLE post_saves (
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    post_id    uuid        NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, post_id)
);

-- One level of replies: [parent_id] always points at a top-level comment.
CREATE TABLE post_comments (
    id         uuid        PRIMARY KEY,
    post_id    uuid        NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    author_id  uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    parent_id  uuid        REFERENCES post_comments (id) ON DELETE CASCADE,
    body       text        NOT NULL CHECK (char_length(body) BETWEEN 1 AND 500),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX post_comments_post_idx ON post_comments (post_id, created_at);
