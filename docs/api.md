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

- Polls and scheduled posts aren't implemented. `poll` or `scheduled_at` with a value gets a 422 rather than being
  ignored, and so does `poll` with a value in an edit.
- `media_ids` can have up to 4 of the user's uploads that aren't attached yet, as on Mastodon. A video or audio can't be
  attached with anything else, and a status with media can have no text. In an edit, `media_ids` lists every attachment
  the status is to have, in order, and a missing one keeps them. `media_attributes[]` only changes `description` and
  `focus`, and a `thumbnail` in it gets a 422. An attachment that an edit takes out is deleted at once.
- `DELETE /api/v1/statuses/:id` keeps the status's attachments so that they can be posted again, as on Mastodon, but for
  an hour by default rather than a day. `delete_media=true` deletes them at once.
- `PUT /api/v1/statuses/:id` keeps the status's `language` when it's missing or empty, as Mastodon does, so a language
  can't be removed. A missing `sensitive` keeps what the status has. `visibility` and `in_reply_to_id` are ignored. An
  account that an edit no longer mentions can still see the status, as on Mastodon. An edit that changes nothing doesn't
  set `edited_at`. Each `edited_at` is at least a second after the version before it, even if that's slightly in the
  future, because other servers are sent whole seconds.
- `GET /api/v1/statuses/:id/history` only has the versions of a status from another server that were sent here, so it
  can lack some that its server shows, and it has no versions from before the upgrade that added it. Only the latest 50
  versions of a status are kept. Only the current version has `media_attachments`, and earlier ones have none. Entries
  have empty `emojis`, and a null `poll`.
- URLs, mentions, and hashtags in a status are linked. A mention of an account on another server is looked
  up when the status is posted, and counts toward the same limit of 30 lookups a minute as `lookup` and `search`. A
  mention that can't be found, or one past that limit of an account that isn't already known here, stays as text rather
  than failing the request.
- The length limit defaults to 5000 characters rather than 500. As in Mastodon, each URL counts as 23 characters, unless
  it's longer than 2048 characters, in which case it counts at its full length. URLs can save at most 4096 characters
  per status, and any URLs after that also count at their full length. A mention counts without its domain, as in
  Mastodon, and the domains can save at most 4096 characters per status.
- A hashtag needs a letter, and can be up to 100 characters. Tags are matched without regard to case or Unicode
  composition, and `tags[].name` is the normalized form: lowercase, in Unicode's NFKC form. A status keeps at most 100
  tags. `tags[].url` is this server's page for the tag, even on a status from another server, as on Mastodon. Statuses
  posted before the upgrade that added hashtags have no `tags`, and their hashtags aren't linked, until they're edited.
- A followers-only or direct status can be seen by the accounts it mentions, as on Mastodon. Mentions from accounts
  the user does not follow appear in notifications, and direct messages in conversations, rather than in the home timeline.
- `mentions` leaves out accounts that have been suspended or deleted since. For a status from another server, it also
  leaves out local accounts that the status was sent to without mentioning them, which can still see it.
- `visibility` defaults to `public` only when it's missing. An empty value is rejected, as are a repeated value and
  `visibility[]`.
- `GET /api/v1/instance` reports the length limit in `max_toot_chars` and `configuration.statuses.max_characters`, and
  the media limits in `configuration.media_attachments`, where `video_size_limit` is the larger of the video and audio
  limits. `video_frame_rate_limit` and `video_matrix_limit` aren't enforced, since video isn't decoded. But
  `/api/v2/instance` isn't implemented. Clients that don't read the limit from v1 may apply their own limit of 500. Its
  `version` is a Mastodon version followed by `(compatible; Elysium)`. The streaming API isn't implemented, though
  `urls.streaming_api` is set. `invites_enabled` is always `false`, since only admins can make invites, from the command
  line. People sign up on the server's `/signup` page, since `POST /api/v1/accounts` isn't implemented.
- `Idempotency-Key` is accepted but ignored, so a client that retries a request can create a duplicate status.
- Statuses from other servers are only stored when they're sent here, which is when a local account follows their author or
  is addressed by them, or when they're a public or unlisted reply to a stored status of a local account or one that's
  followed here. When a local account starts following someone, up to 40 of their recent public and unlisted statuses are
  fetched as well, and these are ordered by when they were posted, so a client that's already loaded newer statuses
  won't be given them as new. Other older ones, and replies that arrive before their parent, aren't there. Their HTML is sanitized, so it
  can differ from what the other server shows, and they never have `emojis` or a `poll`. Their `media_attachments` are
  copied here shortly after they arrive, as Mastodon does, and until then, or if they can't be, `url` and an image's
  `preview_url` are the same as `remote_url`. A copy is checked like an upload, so its type is what the file is, and a
  file that isn't an accepted format is `unknown`. Copies are removed after 30 days by default, and aren't fetched
  again, so older ones link to their server. Up to 16 are kept, more than Mastodon shows. One from a server or account
  whose media an admin rejects is `unknown`, with `url` the same as `remote_url`, and no `preview_url`. Their
  `tags` are the hashtags their server listed for them, and are replaced when they're edited.
  Their `mentions` only list local accounts, and accounts on other servers that are already known here. A reply whose
  parent isn't stored has a null `in_reply_to_id` and `in_reply_to_account_id`.
- `GET /api/v1/statuses/:id/context` only lists statuses stored here, and never fetches missing parents or replies from other
  servers. It lists up to 40 ancestors, and up to 60 descendants at most 20 replies deep, the limits Mastodon uses without a
  token, whoever asks. Statuses the user can't see are left out after those limits apply, so fewer can be listed.
- `GET /api/v1/timelines/home` is worked out from the statuses stored here each time, rather than kept as a feed, so it
  never answers `206`, and an account's stored statuses show as soon as it's followed, older ones too. A followed
  account's reply is only listed when it's to its author, to the user, or to someone they follow, as on Mastodon, and one
  to a status that isn't stored here is left out. Direct messages addressed to the user are included only when they
  follow the author. The user's own statuses and replies are always listed. `local` has no effect. List timelines aren't implemented, and
  followed hashtags don't add statuses to it.
- `GET /api/v1/timelines/public` lists public statuses from other servers too, as on Mastodon. With both `local` and
  `remote`, `local` wins. Boosts aren't listed there, as on Mastodon.
- `GET /api/v1/timelines/tag/:hashtag` lists public statuses with the tag, replies included, from this server and others,
  as on Mastodon, with the same `local`, `remote`, and `only_media` as the public timeline. `any[]`, `all[]`, and
  `none[]` each use their first 4 names, as on Mastodon. A `:hashtag` that isn't a valid name, or a name in `all[]` that
  isn't one, gives an empty list.
- `GET /api/v1/tags/:id` finds any valid tag name, even one that nothing uses, and gets a 404 for an invalid one. Any token
  works, or none. `history` counts only public statuses stored here, over the last 7 days. Tags can't be followed or
  featured, so `following` is always false, and is left out without a user token. The endpoints for following,
  unfollowing, featuring, and trending tags, and `GET /api/v1/followed_tags`, aren't implemented.
- `POST /api/v1/statuses/:id/reblog` boosts a public or unlisted status, or one of the user's own followers-only statuses,
  whose boost is always followers-only. Any other status gets a 404. `visibility` can be `public`, the default, `unlisted`,
  or `private`, sent as a form, a query parameter, or JSON, and `direct` gets a 422. Boosting a status again returns the
  boost that's there. A boost's ID stands for the status it boosts when it's boosted, favourited, replied to, or asked for
  its context, and `DELETE /api/v1/statuses/:id` with a boost's ID unboosts it. `GET /api/v1/statuses/:id/reblogged_by`
  isn't implemented.
- A boost from another server is only stored when it's of a local status, or when a local account follows the booster, so
  a remote account's other boosts aren't listed with its statuses. A boosted status that isn't stored yet is fetched first,
  so the boost can show up a little later than on Mastodon. `reblogs_count` only counts the boosts this server knows of.
  The home timeline lists every boost from the accounts the user follows, even of a status that's already there.

Uploads follow [Mastodon's media API](https://docs.joinmastodon.org/methods/media/), with the `write:media` scope, and
with these differences:

- `POST /api/v2/media` processes the file before it answers, so it always answers 200 with a `url`, never 202.
  `POST /api/v1/media` does the same.
- Uploads can be PNG, JPEG, GIF, or WebP images, MP4, MOV, or WebM video, or MP3, M4A, Ogg, FLAC, or WAV audio. A file's
  type is worked out from its content, and anything else gets a 422, whatever it's sent as.
- Images are re-encoded as WebP, without their metadata, and scaled down to fit 3840 × 2160 pixels in area. Their
  `preview_url` fits 640 × 360. An animated GIF becomes an animated WebP of type `image`, never `gifv`. An image with more
  than 30 million pixels gets a 422, and so does an animation with more than 300 frames, or with more than 25 million
  pixels in all its frames together, such as 100 frames of 500 × 500.
- Video and audio are kept as they were uploaded, so `meta` has no `original` for them, and they have no `preview_url`
  unless a `thumbnail` is uploaded with them. A `thumbnail` with an image gets a 422, and so does one in
  `PUT /api/v1/media/:id`.
- Images, video, and audio each have their own size limit, 16 MB, 100 MB, and 40 MB by default, and a file over its
  limit gets a 422. A request that's larger than any upload can be gets a 413.
- A user can have at most 20 uploads waiting to be posted by default, and one more gets a 422. An upload that isn't
  posted within a day, by default, is deleted.
- `blurhash` is always null, and so are `preview_remote_url` and `text_url`.
- `GET` and `PUT /api/v1/media/:id` only find uploads that aren't attached to a status yet, as on Mastodon. In a `PUT`, a
  missing `description` or `focus` keeps what the upload has, and an empty `description` removes it.
- `DELETE /api/v1/media/:id` gets a 422 for an upload that's attached to a status.

`only_media` works on `GET /api/v1/timelines/public`, `GET /api/v1/timelines/tag/:hashtag`, and
`GET /api/v1/accounts/:id/statuses`, and leaves out boosts.

Notifications follow [Mastodon's notifications API](https://docs.joinmastodon.org/methods/notifications/) and
[grouped notifications API](https://docs.joinmastodon.org/methods/grouped_notifications/):

- `GET /api/v1/notifications`, `GET /api/v1/notifications/:id`, `POST /api/v1/notifications/clear`,
  `POST /api/v1/notifications/:id/dismiss`, and `GET /api/v1/notifications/unread_count` are implemented.
- V2 implements `GET /api/v2/notifications`, `GET /api/v2/notifications/:group_key`,
  `GET /api/v2/notifications/:group_key/accounts`, `POST /api/v2/notifications/:group_key/dismiss`, and
  `GET /api/v2/notifications/unread_count`. V1's clear endpoint clears notifications from both views.
- Only `mention`, `follow`, `follow_request`, `favourite`, and `reblog` notifications are generated. Subscribed-account
  posts, edits, and admin notifications await their respective features. As on Mastodon, a `follow_request` notification
  is removed once the request is approved, refused, or withdrawn, and approving it doesn't add a `follow` notification.
- Notifications start with new activity after upgrading; historical mentions and follows do not generate notifications.
  Inbox updates may appear shortly after their source activity is stored. Silent mentions and self-mentions do not notify.
- Notification policies and notification requests are not implemented. `include_filtered` has no effect, and
  `supported_types[]` has no effect because generated types have no fallback representations. Notifications are not
  suppressed by a per-domain quota. Clients poll; streaming and push subscriptions are not implemented.
- Notification pages default to 40 results, with a maximum of 80; unread counts default to a cap of 100, with a maximum
  of 1000. V2 groups follow events within a span of up to twelve hours, and favourites and reblogs by status, while mentions
  remain individual groups.
  `types[]`, `exclude_types[]`, and `account_id` filter both lists and counts; exclusions win over inclusions.
- Suspended, deleted, or domain-blocked senders are omitted, as are notifications about deleted statuses.
  Reads do not mark notifications as read, and dismissing a conversation does not dismiss its notifications.

Conversations follow [Mastodon's conversations API](https://docs.joinmastodon.org/methods/conversations/):

- `GET /api/v1/conversations`, `DELETE /api/v1/conversations/:id`, and `POST /api/v1/conversations/:id/read`
  are implemented. Pages default to 20 conversations, with a maximum of 40. Follow the `Link` header: cursors are
  latest-status IDs, distinct from conversation IDs.
- A conversation is a thread of direct statuses for one participant set. A new top-level message starts a separate
  conversation even with the same participants. Only stored statuses are included; missing remote parents are never
  fetched. Thread parts join when a missing parent arrives, which can merge provisional conversation entries.
- Existing direct messages appear as read conversations after upgrading. New incoming messages make a conversation
  unread; sending a message or marking it read clears that flag. Deletion hides the conversation only for the user,
  until a newer message arrives, and does not delete statuses. Notification read markers are independent.
- As in Mastodon, silent mentions do not create recipient conversations. Suspended, deleted, and domain-blocked
  accounts are omitted from conversation accounts and message summaries.

Timeline positions follow [Mastodon's markers API](https://docs.joinmastodon.org/methods/markers/):

- `GET /api/v1/markers` and `POST /api/v1/markers` support `home` and `notifications` with Mastodon's scopes.
  Reads without `timeline[]` return `{}`. Writes accept JSON, form, or multipart bodies and return marker versions and
  timestamps. Saving a notifications marker controls both notification unread counts without dismissing notifications.
- Positions and paging cursors must be UUIDs. Invalid values receive 422; concurrent marker updates can receive 409.
  Missing or other users' notification and conversation IDs receive 404. These endpoints require user tokens; an app
  token receives 422.

Accounts and follows follow [Mastodon's accounts API](https://docs.joinmastodon.org/methods/accounts/) and
[search API](https://docs.joinmastodon.org/methods/search/), with these differences:

- Only `GET /api/v1/accounts/:id`, `verify_credentials`, `lookup`, `relationships`, `:id/statuses`, `:id/followers`,
  `:id/following`, `POST /:id/follow` and `/:id/unfollow`, `PATCH update_credentials`, `DELETE /api/v1/profile/avatar`
  and `/api/v1/profile/header`, `GET /api/v1/follow_requests`, `POST /api/v1/follow_requests/:account_id/authorize` and
  `/reject`, and `GET /api/v2/search` exist.
- Profiles of accounts on other servers aren't stored yet, so theirs have the username as `display_name`, an empty `note`
  and `fields`, and placeholder images. For an account on another server, `followers_count` and `following_count` only
  count accounts on this server, `hide_collections` is null, and `locked` is false, even when that server approves its
  followers by hand.
- `update_credentials` can change `display_name`, `note`, `fields_attributes`, `avatar`, `header`, `locked`, and
  `hide_collections`. `bot`, `discoverable`, `indexable`, and `source` are accepted only with the values the account
  already has: false, and `public` for `source[privacy]`. Any other value for them, or any other field with a value, gets a 422 naming
  it, and then nothing is changed. Empty values are ignored, except that an empty `display_name` or `note` clears it, as
  on Mastodon.
- `fields_attributes` replaces all of the fields. It can be a list or an object keyed by position, in JSON or in a form
  (`fields_attributes[0][name]` or `fields_attributes[][name]`). Fields with an empty name and value are left out. How many
  there can be is set by the admin, and defaults to 4: `GET /api/v1/instance` reports it in
  `configuration.accounts.max_profile_fields`, which Mastodon doesn't send.
- `avatar` and `header` can only be uploaded in a multipart form, not as data in JSON. They have to be a PNG, JPEG, GIF,
  or WebP, 8 MB at most by default. They're always re-encoded as WebP and cropped to 400 × 400 and 1500 × 500, which
  removes their metadata. Animated ones stay animated, and `avatar_static` and `header_static` are their first frame.
  `DELETE /api/v1/profile/avatar` and `/api/v1/profile/header` remove them.
- A field whose value is only an `https` URL gets a `verified_at` once that page links back to the account with
  `rel="me"`. This server checks when the field is saved, and only follows 3 redirects. A field keeps its `verified_at`
  while its value stays the same.
- Following an account on another server returns `requested: true` until that server accepts, even if it doesn't approve
  followers by hand. Following yourself, or an account on a blocked server, gets a 422.
- Turning `locked` off approves the requests that are waiting, as on Mastodon, but in the background, so they can still be
  listed just after the response. Turning it on keeps the account's followers.
- `GET /api/v1/follow_requests` pages by the request, like `:id/followers`, and leaves out suspended and deleted accounts.
  `authorize` and `reject` get a 404 when that account has no request waiting.
- `reblogs`, `notify`, and `languages` are ignored when following, and a relationship's `showing_reblogs` is true whenever
  `following` or `requested` is. Blocks, mutes, notes, and endorsements don't exist, so those fields are always false or empty.
- A suspended account gets a 404 from `GET /api/v1/accounts/:id` and the lists, and is left out of `relationships`, even
  with `with_suspended`, and of followers and following. An account can still be unfollowed while it's suspended.
- `:id/followers` and `:id/following` page by the follow, so a cursor from a follow that's since been removed gives an empty
  page. If the account hides them, anyone else gets an empty list.
- `lookup` and `search` only ask another server about an account for a signed-in user, and `search` only when `resolve` is
  true. Each user can make 30 of those lookups a minute, and then gets a 429.
- `search` finds accounts and hashtags. `statuses` is always empty, and a `type` of `statuses` returns nothing.
  A query that's a `username@domain` or an `https` URL finds that one account. Any other query matches the start of the
  usernames this server knows. Only the first 200 matches can be paged through with `offset`, and an `offset` past them
  returns no accounts. `following`, `account_id`, `exclude_unreviewed`,
  `min_id`, and `max_id` are ignored.
- `search` finds hashtags by the start of their name, from those that public statuses stored here use, most used first.
  A query that's a valid name, with or without its `#`, also finds that tag when nothing uses it, as the first result,
  as on Mastodon. Only the first 200 can be paged through with `offset`.
- `GET /api/v1/accounts/:id/statuses` for an account on another server only lists its statuses stored here.

Domain blocks follow [Mastodon's admin API](https://docs.joinmastodon.org/methods/admin/domain_blocks/), under
`/api/v1/admin/domain_blocks`, with the `admin:read:domain_blocks` and `admin:write:domain_blocks` scopes, with these
differences from Mastodon:

- `severity` is required when creating a block, and has to be `suspend` or `noop`. Mastodon's default is `silence`, which
  isn't supported, and gets a 422 here rather than a block that does more or less than an admin meant.
- `reject_media` works on a `noop` block. A `suspend` block always rejects media, and is returned with `reject_media` and
  `reject_reports` as `true`. Reports aren't supported, so a `noop` block's `reject_reports` is `false`. `obfuscate` is
  always `false`, and `public_comment` null, and any values sent for `reject_reports`, `obfuscate`, and `public_comment`
  are ignored.
- `PUT` changes only what it's sent, as on Mastodon, and an empty `private_comment` removes it. Changing a block to
  `suspend`, or turning on `reject_media`, removes what that block removes, and changing it back doesn't bring that back.
- A domain with a wildcard, a port, or a URL's other parts is refused, as are IP addresses, a domain with only one label such
  as `com`, and this server's own domain or one above it. Blocks always cover subdomains and every port. A Unicode domain
  that converting would change, such as `faß.example`, has to be sent in its `xn--` form.
- `private_comment` can be up to 1000 characters.
- A domain that a broader block already covers gets a 422 with `existing_domain_block`, like one that's already blocked, unless the new block does more, such as suspending a subdomain of a `noop` block.

Accounts follow [Mastodon's admin API](https://docs.joinmastodon.org/methods/admin/accounts/), under `/api/v1/admin/accounts`,
with the `admin:read:accounts` and `admin:write:accounts` scopes, and these differ from Mastodon:

- Only `GET /api/v1/admin/accounts` and `/:id`, `POST /:id/action`, `POST /:id/unsuspend`, and `DELETE /:id` exist. There's no
  `/api/v2/admin/accounts`.
- `GET /api/v1/admin/accounts` only filters by `local`, `remote`, `suspended`, `username`, which matches the start of the
  username, and `by_domain`. Accounts are ordered by their random IDs, not by age, and deleted accounts aren't listed.
- An account only has `id`, `username`, `domain`, `email`, `created_at`, `role`, `suspended`, and `account`, and `role` only
  has a `name`, of `Admin` or `User`, or is null for a remote account. `email` is null for a remote account, and for a local
  one that signed up with an invite or was made from the command line.
- Sign-ups waiting for approval aren't accounts yet, so they aren't listed, and the `pending` filter and the `approve` and
  `reject` endpoints don't exist. Admins approve them on the `/admin/sign-ups` page.
- `type` has to be `suspend`, and other values get a 422, rather than doing more than an admin meant. Other parameters are
  ignored.
- `DELETE` doesn't need the account to be suspended first. It's only for local accounts, and gets a 403 for a remote one.
- Suspending or deleting yourself, or the server's last admin, gets a 403, with the reason in `error`.

There's no API for people to delete their own accounts, so that an application can't do it with a token. It's only on the
`/settings/delete-account` page. People can also hide their followers and following on the `/settings` page.

API documentation is public, with Swagger UI at `/api-docs/ui` and the OpenAPI document at `/api-docs` or
`/api-docs.yaml`. It describes each endpoint and how it differs from Mastodon, with a link to Mastodon's documentation for
it, and includes the OAuth endpoints, the scopes each endpoint needs, and the ActivityPub, WebFinger, and NodeInfo
endpoints that other servers use. A scope such as `read` also grants
the granular scopes under it. To sign in from Swagger UI, first register an application with the redirect URI
`<base-url>/api-docs/swagger-ui/oauth2-redirect.html`, and then enter its `client_id` and `client_secret` under Authorize.
Swagger UI builds that redirect URI from the request, so behind a reverse proxy, the server has to
[trust the proxy's forwarded headers](configuration.md#reverse-proxies), or the sign-in is refused.
