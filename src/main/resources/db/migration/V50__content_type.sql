-- What a local status's text was written in, which decides how it's rendered, and which an edit starts from. A status
-- from another server is always text/plain, since its text is what its HTML shows.
ALTER TABLE status
    ADD COLUMN content_type TEXT NOT NULL DEFAULT 'text/plain' CHECK (content_type IN ('text/plain', 'text/markdown'));
