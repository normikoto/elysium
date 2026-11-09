-- The hashtags a status has, by their normalized name. There's no table of tags: one exists while any status has it, so
-- removing statuses, as when an account is deleted or a domain blocked, removes its tags with nothing more to clean up.
CREATE TABLE status_tag
(
    status_id UUID NOT NULL REFERENCES status (id) ON DELETE CASCADE,
    name      TEXT NOT NULL,
    PRIMARY KEY (status_id, name)
);

-- For a tag's timeline, newest first, and for searching tags by the start of their name.
CREATE INDEX status_tag_name_idx ON status_tag (name text_pattern_ops, status_id DESC);
