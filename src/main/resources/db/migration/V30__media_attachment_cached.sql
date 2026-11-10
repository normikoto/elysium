-- For removing old copies of other servers' attachments, which looks for them every few minutes.
CREATE INDEX media_attachment_cached_idx ON media_attachment (cached_at)
    WHERE file_name IS NOT NULL AND remote_url IS NOT NULL;
