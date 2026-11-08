-- For cancelling every activity whose ID starts with the same text, such as each edit's Update of a deleted status. The
-- primary key compares in the database's collation, which a prefix can't be looked up in, so this one compares bytes.
CREATE INDEX outbound_activity_id_bytes_idx ON outbound_activity (activity_id COLLATE "C");
