-- bot, which V46 added for accounts on other servers, now also says whether a local account is automated, and is sent as
-- the type of its actor document. cat is Misskey's isCat, for both.
ALTER TABLE profile
    ADD COLUMN cat BOOLEAN NOT NULL DEFAULT false;
