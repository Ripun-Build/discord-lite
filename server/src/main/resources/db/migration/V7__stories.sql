-- Phase 6: stories. Friends see a story for 24 hours; after that only its author does (their
-- private archive) until they delete it. Not end-to-end encrypted. The photo itself lives in the
-- media store under [media_key]; this table only describes it.

CREATE TABLE stories (
    id         uuid        PRIMARY KEY,
    author_id  uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    media_key  text        NOT NULL UNIQUE,
    width      int         NOT NULL,
    height     int         NOT NULL,
    caption    text        CHECK (caption IS NULL OR char_length(caption) BETWEEN 1 AND 200),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX stories_author_idx ON stories (author_id, created_at DESC);

-- Who has seen each story ("Seen by"). Only the author can read this.
CREATE TABLE story_views (
    story_id  uuid        NOT NULL REFERENCES stories (id) ON DELETE CASCADE,
    viewer_id uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    viewed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (story_id, viewer_id)
);
