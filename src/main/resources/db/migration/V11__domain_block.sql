-- Servers that this one has cut off. A block on a domain also covers its subdomains.
CREATE TABLE domain_block
(
    id              UUID PRIMARY KEY,
    domain          TEXT        NOT NULL UNIQUE,
    private_comment TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Goes through a blocked server's actors in order, to remove their follows.
CREATE INDEX actor_remote_domain_idx ON actor (domain, id) WHERE NOT is_local;
