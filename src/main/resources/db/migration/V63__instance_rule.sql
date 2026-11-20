-- The server's rules, which clients show at sign-up and in their about screens, ordered by position as Mastodon orders
-- them by priority. Positions aren't unique, so that two admins reordering at once can't fail each other, and ties go by
-- id. Rules are deleted outright: Mastodon keeps deleted ones because reports cite them, which reports here can't yet.
CREATE TABLE instance_rule
(
    id         UUID PRIMARY KEY,
    text       TEXT        NOT NULL,
    hint       TEXT        NOT NULL DEFAULT '',
    position   INTEGER     NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
