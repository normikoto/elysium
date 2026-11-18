-- Copies of other servers' attachments that were removed after elysium.attachments.remote-cache-retention used to be
-- linked from then on. Now they're downloaded again when they're next viewed, so those removed before this go back to not
-- having been tried. Only a copy sets file_size on a remote attachment, which tells them apart from ones that were given up.
UPDATE media_attachment SET cached_at = NULL
WHERE remote_url IS NOT NULL AND file_name IS NULL AND file_size IS NOT NULL AND cached_at IS NOT NULL;
