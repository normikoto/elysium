-- How many statuses each actor has, leaving out direct ones. Counting them on every read would mean scanning every status
-- of every account in a response. Triggers keep this correct however statuses are deleted, including when deleting an
-- actor cascades to its statuses. They assume a status's actor and visibility never change, and TRUNCATE skips them.
CREATE TABLE actor_status_count
(
    actor_id       UUID PRIMARY KEY REFERENCES actor (id) ON DELETE CASCADE,
    statuses_count BIGINT NOT NULL CHECK (statuses_count >= 0)
);

INSERT INTO actor_status_count (actor_id, statuses_count)
SELECT actor_id, count(*)
FROM status
WHERE visibility <> 'direct'
GROUP BY actor_id;

CREATE FUNCTION count_inserted_status() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    INSERT INTO actor_status_count (actor_id, statuses_count)
    VALUES (NEW.actor_id, 1)
    ON CONFLICT (actor_id) DO UPDATE
        SET statuses_count = actor_status_count.statuses_count + 1;
    RETURN NULL;
END;
$$;

CREATE FUNCTION count_deleted_statuses() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    UPDATE actor_status_count c
    SET statuses_count = c.statuses_count - d.statuses_count
    FROM (SELECT actor_id, count(*) AS statuses_count
          FROM deleted
          WHERE visibility <> 'direct'
          GROUP BY actor_id) d
    WHERE c.actor_id = d.actor_id;
    RETURN NULL;
END;
$$;

CREATE TRIGGER status_count_insert
    AFTER INSERT
    ON status
    FOR EACH ROW
    WHEN (NEW.visibility <> 'direct')
EXECUTE FUNCTION count_inserted_status();

-- This runs once per statement rather than once per row, so deleting an account with many statuses updates its count once.
CREATE TRIGGER status_count_delete
    AFTER DELETE
    ON status
    REFERENCING OLD TABLE AS deleted
    FOR EACH STATEMENT
EXECUTE FUNCTION count_deleted_statuses();

-- Finds an actor's latest counted status without reading the others.
CREATE INDEX status_actor_published_idx ON status (actor_id, published_at DESC) WHERE visibility <> 'direct';
