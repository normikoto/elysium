-- Statuses that local accounts have asked to be posted later. Nothing about one leaves this table until it's posted, when
-- it's posted as a new status, so nothing that reads statuses has to know about these.
CREATE TABLE scheduled_status
(
    id           UUID PRIMARY KEY,
    actor_id     UUID        NOT NULL REFERENCES actor (id) ON DELETE CASCADE,
    scheduled_at TIMESTAMPTZ NOT NULL,
    -- What the status is to be posted with, other than its media, as the request gave it. It's checked again when it's
    -- posted, since what it replies to or quotes can change in between.
    params       JSONB       NOT NULL,
    -- How many times posting it has failed for a reason other than it no longer being postable, and when to try again,
    -- so that one that keeps failing waits longer each time, and is dropped in the end, rather than being tried every
    -- run and holding up the others.
    failures     SMALLINT    NOT NULL DEFAULT 0,
    retry_at     TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX scheduled_status_due_idx ON scheduled_status (scheduled_at);
CREATE INDEX scheduled_status_actor_idx ON scheduled_status (actor_id, id);

-- Uploads held for a scheduled status, which can't be attached to anything else, and aren't removed while they're held.
-- They have no removable_after while they are, and are given one again when they stop being held, so that the sweep
-- for unattached uploads only has to skip held ones.
ALTER TABLE media_attachment
    ADD COLUMN scheduled_status_id UUID REFERENCES scheduled_status (id) ON DELETE SET NULL;

CREATE INDEX media_attachment_scheduled_idx ON media_attachment (scheduled_status_id, position)
    WHERE scheduled_status_id IS NOT NULL;
