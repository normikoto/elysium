-- When each host's limit on fetches made on another server's word is next free, and each sender's share of it. The row
-- with an empty sender is the host's own. A row whose time has passed is the same as no row.
CREATE TABLE fetch_turn
(
    target    text        NOT NULL,
    sender    text        NOT NULL,
    next_free timestamptz NOT NULL,
    PRIMARY KEY (target, sender)
);

CREATE INDEX fetch_turn_next_free_idx ON fetch_turn (next_free);
