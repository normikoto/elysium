# Using the API

Applications authenticate with OAuth 2, following
[Mastodon's OAuth API](https://docs.joinmastodon.org/methods/oauth/): `POST /api/v1/apps`, `GET /oauth/authorize`,
`POST /oauth/token`, and `POST /oauth/revoke`, with discovery at `/.well-known/oauth-authorization-server`. Elysium
differs from Mastodon in these ways:

- `force_login`, `prompt`, `lang`, `/oauth/userinfo`, and `POST /api/v1/accounts` aren't implemented.
- Each authorization issues a new token rather than reusing the application's existing one.
- Revoking an unknown token succeeds, rather than returning the 403 that Mastodon documents.
- `/oauth/token` and `/oauth/revoke` only accept form-encoded bodies, not JSON.

Statuses follow [Mastodon's statuses API](https://docs.joinmastodon.org/methods/statuses/), with these differences:

- Statuses can't be edited, and media, polls, scheduled posts, and `GET /api/v1/statuses/:id/context` aren't implemented.
- The length limit defaults to 5000 characters rather than 500. As in Mastodon, each URL counts as 23 characters, unless
  it's longer than 2048 characters, in which case it counts at its full length. URLs can save at most 4096 characters
  per status, and any URLs after that also count at their full length.
- `visibility` defaults to `public` only when it's missing. An empty value is rejected, as are a repeated value and
  `visibility[]`.
- `GET /api/v1/instance` reports the limit in `max_toot_chars` and `configuration.statuses.max_characters`, but
  `/api/v2/instance` isn't implemented. Clients that don't read the limit from v1 may apply their own limit of 500.
- `Idempotency-Key` is accepted but ignored, so a client that retries a request can create a duplicate status.

Accounts and follows follow [Mastodon's accounts API](https://docs.joinmastodon.org/methods/accounts/) and
[search API](https://docs.joinmastodon.org/methods/search/), with these differences:

- Only `GET /api/v1/accounts/:id`, `verify_credentials`, `lookup`, `relationships`, `:id/statuses`, `:id/followers`,
  `:id/following`, `POST /:id/follow` and `/:id/unfollow`, `PATCH update_credentials`, and `GET /api/v2/search` exist.
- Accounts have no profile yet, so `display_name` is the username, `note` is empty, and images are a placeholder. For an
  account on another server, `followers_count` and `following_count` only count accounts on this server, and
  `hide_collections` is null.
- `update_credentials` can only change `hide_collections`. Any other field with a value gets a 422 naming it, and nothing is
  changed. Empty values are ignored.
- Following a local account takes effect straight away. Following an account on another server returns `requested: true`
  until that server accepts. Following yourself, or an account on a blocked server, gets a 422.
- `reblogs`, `notify`, and `languages` are ignored when following, and a relationship's `showing_reblogs` is true whenever
  `following` or `requested` is. Blocks, mutes, notes, and endorsements don't exist, so those fields are always false or empty.
- A suspended account gets a 404 from `GET /api/v1/accounts/:id` and the lists, and is left out of `relationships` and of
  followers and following. An account can still be unfollowed while it's suspended.
- `:id/followers` and `:id/following` page by the follow, so a cursor from a follow that's since been removed gives an empty
  page. If the account hides them, anyone else gets an empty list.
- `lookup` and `search` only ask another server about an account for a signed-in user, and `search` only when `resolve` is
  true. Each user can make 30 of those lookups a minute, and then gets a 429.
- `search` only finds accounts. `statuses` and `hashtags` are always empty, and a `type` other than `accounts` returns nothing.
  A query that's a `username@domain` or an `https` URL finds that one account. Any other query matches the start of the
  usernames this server knows, and `offset` can go up to 200 results in. `following`, `account_id`, `exclude_unreviewed`,
  `min_id`, and `max_id` are ignored.
- Statuses from other servers aren't stored yet, so following an account there doesn't add anything to a timeline.

Domain blocks follow [Mastodon's admin API](https://docs.joinmastodon.org/methods/admin/domain_blocks/), under
`/api/v1/admin/domain_blocks`, with the `admin:read:domain_blocks` and `admin:write:domain_blocks` scopes. Every block is a
full block, so these differ from Mastodon:

- `severity` is required when creating a block, and has to be `suspend`. Mastodon's default is `silence`, and `silence` and
  `noop` get a 422 here rather than cutting off a server that an admin only meant to limit.
- `reject_media` and `reject_reports` are always returned as `true`, `obfuscate` as `false`, and `public_comment` as null.
  Any values sent for them are ignored.
- A domain with a wildcard, a port, or a URL's other parts is refused, as are IP addresses, a domain with only one label such
  as `com`, and this server's own domain or one above it. Blocks always cover subdomains and every port. A Unicode domain
  that converting would change, such as `faß.example`, has to be sent in its `xn--` form.
- `private_comment` can be up to 1000 characters.
- A domain that a broader block already covers gets a 422 with `existing_domain_block`, like one that's already blocked.

Accounts follow [Mastodon's admin API](https://docs.joinmastodon.org/methods/admin/accounts/), under `/api/v1/admin/accounts`,
with the `admin:read:accounts` and `admin:write:accounts` scopes, and these differ from Mastodon:

- Only `GET /api/v1/admin/accounts` and `/:id`, `POST /:id/action`, `POST /:id/unsuspend`, and `DELETE /:id` exist. There's no
  `/api/v2/admin/accounts`.
- `GET /api/v1/admin/accounts` only filters by `local`, `remote`, `suspended`, `username`, which matches the start of the
  username, and `by_domain`. Accounts are ordered by their random IDs, not by age, and deleted accounts aren't listed.
- An account only has `id`, `username`, `domain`, `created_at`, `role`, `suspended`, and `account`, and `role` only has a `name`,
  of `Admin` or `User`, or is null for a remote account.
- `type` has to be `suspend`, and other values get a 422, rather than doing more than an admin meant. Other parameters are
  ignored.
- `DELETE` doesn't need the account to be suspended first. It's only for local accounts, and gets a 403 for a remote one.
- Suspending or deleting yourself, or the server's last admin, gets a 403, with the reason in `error`.

There's no API for people to delete their own accounts, so that an application can't do it with a token. It's only on the
`/settings/delete-account` page. People can also hide their followers and following on the `/settings` page.

API documentation is public, with Swagger UI at `/api-docs/ui` and the OpenAPI document at `/api-docs` or
`/api-docs.yaml`.
