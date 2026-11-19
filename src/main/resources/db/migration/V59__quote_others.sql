-- quote_policy now says whose quotes are approved automatically, and quote_others whether everyone else's are rejected
-- or reviewed by hand, instead of quote_review saying whose quotes, among those the policy allows, are reviewed. Each
-- old setting becomes the new one that approves nothing automatically that the old one didn't. Only followers being
-- allowed to ask, with every quote reviewed, has no equal, and becomes everyone's quotes being reviewed.
CREATE OR REPLACE FUNCTION pg_temp.quote_policy(policy TEXT, review TEXT) RETURNS TEXT
    LANGUAGE SQL IMMUTABLE AS
$$
SELECT CASE
           WHEN policy = 'public' AND review = 'non_followers' THEN 'followers'
           WHEN policy IN ('public', 'followers') AND review = 'all' THEN 'nobody'
           ELSE policy
           END
$$;

CREATE OR REPLACE FUNCTION pg_temp.quote_others(policy TEXT, review TEXT) RETURNS TEXT
    LANGUAGE SQL IMMUTABLE AS
$$
SELECT CASE
           WHEN (policy = 'public' AND review IN ('non_followers', 'all')) OR (policy = 'followers' AND review = 'all')
               THEN 'review'
           ELSE 'reject'
           END
$$;

-- A draft that left both to the account's defaults keeps doing so, since those are converted below. One that gave
-- either has both written out, from the defaults as they were, because half of an old pair can't be converted alone.
UPDATE scheduled_status scheduled
SET params = (scheduled.params - 'quoteReview') || jsonb_build_object(
        'quotePolicy', pg_temp.quote_policy(old.policy, old.review),
        'quoteOthers', pg_temp.quote_others(old.policy, old.review))
FROM (SELECT s.id,
             coalesce(s.params ->> 'quotePolicy', d.quote_policy, 'public') AS policy,
             coalesce(s.params ->> 'quoteReview', d.quote_review, 'none')   AS review
      FROM scheduled_status s
               LEFT JOIN posting_default d ON d.actor_id = s.actor_id
      WHERE s.params ->> 'quotePolicy' IS NOT NULL
         OR s.params ->> 'quoteReview' IS NOT NULL) old
WHERE scheduled.id = old.id;

UPDATE scheduled_status
SET params = params - 'quoteReview'
WHERE params ? 'quoteReview';

ALTER TABLE posting_default ADD COLUMN quote_others TEXT NOT NULL DEFAULT 'reject'
    CHECK (quote_others IN ('reject', 'review'));

UPDATE posting_default
SET quote_policy = pg_temp.quote_policy(quote_policy, quote_review),
    quote_others = pg_temp.quote_others(quote_policy, quote_review);

ALTER TABLE posting_default DROP COLUMN quote_review;

DROP FUNCTION pg_temp.quote_policy(TEXT, TEXT);
DROP FUNCTION pg_temp.quote_others(TEXT, TEXT);
