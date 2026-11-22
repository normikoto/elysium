-- A local account's background, as Pleroma's API has it. It isn't sent to other servers, so only local profiles have one,
-- and there's nothing to download or cache for it.
ALTER TABLE profile
    ADD COLUMN background        TEXT,
    ADD COLUMN background_static TEXT,
    ADD CHECK ((background IS NULL) = (background_static IS NULL));
