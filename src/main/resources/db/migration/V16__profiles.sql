-- What people show about themselves. Only local accounts have one so far. Text is stored as it was written, and rendered
-- when it's read. Images are file names in the media directory, which an animated image has two of.
CREATE TABLE profile
(
    actor_id      UUID PRIMARY KEY REFERENCES actor (id) ON DELETE CASCADE,
    display_name  TEXT        NOT NULL DEFAULT '',
    note          TEXT        NOT NULL DEFAULT '',
    avatar        TEXT,
    avatar_static TEXT,
    header        TEXT,
    header_static TEXT,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((avatar IS NULL) = (avatar_static IS NULL)),
    CHECK ((header IS NULL) = (header_static IS NULL))
);

-- verified_at is when the page that value links to was found to link back to the profile.
CREATE TABLE profile_field
(
    actor_id    UUID    NOT NULL REFERENCES profile (actor_id) ON DELETE CASCADE,
    position    INTEGER NOT NULL,
    name        TEXT    NOT NULL,
    value       TEXT    NOT NULL,
    verified_at TIMESTAMPTZ,
    PRIMARY KEY (actor_id, position)
);
