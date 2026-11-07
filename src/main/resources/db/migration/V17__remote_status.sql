-- For statuses from other servers. A local status has its page at its URI, and its parent is always stored here, so it
-- needs neither. A remote reply keeps its parent's URI even when the parent isn't stored, which is what Mastodon's API
-- needs to show it as a reply, and lets it be linked once the parent is.
ALTER TABLE status
    ADD COLUMN url             TEXT,
    ADD COLUMN in_reply_to_uri TEXT;
