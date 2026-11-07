-- When a remote status was last edited, as its server's Update gave it in `updated`. Jobs have no order, so this is what
-- keeps an older Update from replacing a newer one. Null for a status that was never edited.
ALTER TABLE status
    ADD COLUMN edited_at TIMESTAMPTZ;
