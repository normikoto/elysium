# Using the API

Elysium implements Mastodon's client API, with the differences listed here. Each section links to the Mastodon
documentation it follows.

- A multipart request has to have a `Content-Length`, and one sent with chunked transfer encoding gets a 411. One that's
  larger than its endpoint's files can be, plus 4 MB for the rest of the form, gets a 413 before it's read. That's the
  avatar and header limits for `PATCH /api/v1/accounts/update_credentials`, and the media limits for uploads.

## OAuth

Follows [Mastodon's OAuth API](https://docs.joinmastodon.org/methods/oauth/): `POST /api/v1/apps`,
`GET /oauth/authorize`, `POST /oauth/token`, and `POST /oauth/revoke`, with discovery at
`/.well-known/oauth-authorization-server`.

- `force_login`, `prompt`, `lang`, `/oauth/userinfo`, and `POST /api/v1/accounts` aren't implemented.
- Each authorization issues a new token rather than reusing the application's existing one.
- Revoking an unknown token succeeds, rather than returning Mastodon's 403.
- A JSON body for `/oauth/token` or `/oauth/revoke` can be at most 64 KB, and a larger one gets a 413. A field that's an
  array, or a key given twice, gets a 400 `invalid_request`, as a repeated form field does. Fields that are objects or
  null are ignored.
- `POST /api/v1/apps` allows 30 registrations an hour from one address, not counting ones that get a 422, and then
  returns a 429 with a `Retry-After` header.
- An application that isn't approved or issued a token within a day of registering is removed, and has to register
  again. A token from the `client_credentials` grant keeps it.

## Statuses

Follows [Mastodon's statuses API](https://docs.joinmastodon.org/methods/statuses/).

### Posting

- `scheduled_at` schedules the status instead, and the response is a `ScheduledStatus`. See
  [Scheduled statuses](#scheduled-statuses).
- `Idempotency-Key` is accepted but ignored, so a client that retries a request can create a duplicate status.
- `visibility` defaults to `public` only when it's missing. An empty value, a repeated value, and `visibility[]` are
  rejected.
- `media_ids` can have up to 4 of the user's unattached uploads, as on Mastodon. A video or audio can't be attached with
  anything else, and a status with media can have no text.
- A status with a poll has to have text, and can't have `media_ids`. See [Polls](#polls).
- `DELETE /api/v1/statuses/:id` keeps the status's attachments so that they can be posted again, as on Mastodon, but for
  an hour by default rather than a day. `delete_media=true` deletes them at once.

### Length and links

- The length limit defaults to 5000 characters rather than 500.
- As in Mastodon, each URL counts as 23 characters, unless it's longer than 2048 characters, in which case it counts at
  its full length. URLs can save at most 4096 characters per status, and any after that count at their full length.
- A mention counts without its domain, as in Mastodon, and the domains can save at most 4096 characters per status.
- URLs, mentions, and hashtags are linked. A mention of an account on another server is looked up when the status is
  posted, and counts toward the same limit of 30 lookups a minute as `lookup` and `search`. A mention that can't be
  found, or one past that limit of an account that isn't already known here, stays as text rather than failing the
  request.

### Editing

- In `PUT /api/v1/statuses/:id`, `media_ids` lists every attachment the status is to have, in order, and a missing one
  keeps them. An attachment that an edit takes out is deleted at once.
- `media_attributes[]` only changes `description` and `focus`, and a `thumbnail` in it gets a 422.
- A missing or empty `language` keeps the status's, as on Mastodon, so a language can't be removed. A missing
  `sensitive` keeps what the status has.
- `visibility` and `in_reply_to_id` are ignored.
- An account that an edit no longer mentions can still see the status, as on Mastodon.
- An edit that changes nothing doesn't set `edited_at`. Each `edited_at` is at least a second after the version before
  it, even if that's slightly in the future, because other servers are sent whole seconds.
- `GET /api/v1/statuses/:id/history`:
  - For a status from another server, it only has the versions that were sent here, and none from before the upgrade
    that added it.
  - Only the latest 50 versions are kept.
  - Only the current version has `media_attachments`.
  - An entry's `emojis` leaves out emoji that have been removed since, and is empty for a version from before the
    upgrade that added custom emoji.
  - An entry's `poll` only has each option's `title`, and is null for a version with no poll or from before the upgrade
    that added polls.

### Visibility

- A followers-only or direct status from this server has a `url`, as on Mastodon. Its page is only shown to someone
  logged in to this server's website who can see the status, and anyone else gets a 404.
- A followers-only or direct status can be seen by the accounts it mentions, as on Mastodon. Mentions from accounts the
  user doesn't follow appear in notifications, and direct messages in conversations, rather than in the home timeline.
- `mentions` leaves out accounts that have been suspended or deleted since. For a status from another server, it also
  leaves out local accounts that it was sent to without mentioning them, which can still see it.
- A reply whose parent the viewer can't see, such as a public reply to its author's own followers-only status, has a null
  `in_reply_to_id` and `in_reply_to_account_id`. Mastodon names the parent whoever can see it.
- `local_only=true` keeps a status on this server, as on glitch-soc, alongside any `visibility`.
  - It isn't sent to other servers, and only accounts on this server can see it, through an app or logged in to the
    website. Anyone else gets a 404, and it's left out of what they can list, such as the public timeline and an
    account's statuses.
  - A reply to, quote of, or boost of a local-only status is local-only too. A local-only status that quotes a status
    from another server gets a 422. An edit can't change it.
  - Statuses have `local_only`, which is always false for one from another server.
- `content_type` can be `text/markdown` on a new status or in `PUT /api/v1/statuses/:id`, as on glitch-soc, Pleroma, and
  Akkoma, rather than `text/plain`, which is the default. Anything else, such as `text/html`, gets a 422.
  - An edit with no `content_type` keeps what the status was written in, and `GET /api/v1/statuses/:id/source` has
    `content_type`. `configuration.statuses.supported_mime_types` in `GET /api/v1/instance` and `GET /api/v2/instance`
    lists both.
  - Markdown is CommonMark with `~~strikethrough~~`, and a single new line is a line break. Its `content` only has what
    Mastodon shows: headings are bold paragraphs, an image is a link to it with its description as the text, rules are
    dropped, there are no tables, and only `http` and `https` links are kept. HTML in the text is shown as text.
  - URLs, mentions, and hashtags are linked as in plain text, except in code and in a link's text, where they also don't
    notify anyone or tag the status. The length limit counts the text as written, including its Markdown.

### Emoji and hashtags

- A status's `emojis` are this server's custom emoji that its text, content warning, or poll options name when it's
  posted or edited, so one an admin adds later isn't shown on statuses that named it before. A poll's `emojis` are those
  its options name.
- Shortcodes are matched with case, and a status has at most 100 emoji.
- Emoji images are WebP, and `static_url` is the first frame of an animated one.
- An account's `emojis` are those its display name, note, and fields name. For an account on another server, they're the
  ones its server listed that those name.
- `GET /api/v1/custom_emojis` lists every emoji an admin has added, including those hidden from the picker, and no
  disabled ones, since an admin removes an emoji instead.
- A hashtag needs a letter, and can be up to 100 characters. A status keeps at most 100 tags.
- Tags are matched without regard to case or Unicode composition, and `tags[].name` is the normalized form: lowercase,
  in Unicode's NFKC form.
- `tags[].url` is this server's page for the tag, even on a status from another server, as on Mastodon.
- Statuses posted before the upgrade that added hashtags have no `tags`, and their hashtags aren't linked, until
  they're edited.

### Instance

- `GET /api/v1/instance` reports the length limit in `max_toot_chars` and `configuration.statuses.max_characters`, and
  the media limits in `configuration.media_attachments`, where `video_size_limit` is the larger of the video and audio
  limits. `video_frame_rate_limit` and `video_matrix_limit` aren't enforced, since video isn't decoded.
- `version` is a Mastodon version followed by `(compatible; Elysium)`.
- `invites_enabled` is always `false`, since only admins can make invites, from the command line. People sign up on the
  server's `/signup` page, since `POST /api/v1/accounts` isn't implemented.
- `GET /api/v2/instance` has the same limits in `configuration`, and `api_versions.mastodon` is 7, which is what clients
  look for before they offer quotes. Not everything Mastodon added up to that version is here, as this page lists, and
  what's missing gets a 404.
- `GET /api/v2/instance` has no thumbnail, icon, or contact account. `usage.users.active_month` counts every local
  account, and `configuration.translation.enabled` is false.
- Admins set the server's rules on the `/admin/rules` page. `GET /api/v1/instance/rules` lists them in order, as do
  `rules` in `GET /api/v1/instance` and `GET /api/v2/instance`. Their IDs are UUIDs rather than numbers, and they have
  no `translations`. The sign-up page lists them too.

### Pins

- `POST /api/v1/statuses/:id/pin` and `/unpin` need `write:accounts`, as on Mastodon.
- A user can pin up to 5 statuses, which `configuration.accounts.max_pinned_statuses` says.
- Pinning someone else's status, a boost, or a direct status gets a 422 with Mastodon's message, and so does a sixth
  pin. Unpinning a status that isn't pinned does nothing.
- `pinned` is only true for the user's own statuses.

### Bookmarks

- `POST /api/v1/statuses/:id/bookmark` and `/unbookmark` need `write:bookmarks`, and `GET /api/v1/bookmarks` needs
  `read:bookmarks`. Repeats do nothing more, and a boost's ID stands for the status it boosts.
- Bookmarks are private. Nobody else is told, including the status's author and other servers, and `bookmarked` is only
  true for the user who made it.
- `GET /api/v1/bookmarks` pages by when each status was bookmarked, with the bookmarks' IDs as cursors in the `Link`
  header, as on Mastodon. Statuses the user can no longer see, such as those of an account that blocks them, are left
  out, so a page can be shorter than `limit` and still have more after it. Filters don't hide them, but are given in
  `filtered`.
- Unbookmarking a status the user can no longer see still removes the bookmark, but answers 404.
- A bookmark goes when its status is deleted.

### Muting threads

- `POST /api/v1/statuses/:id/mute` and `/unmute` need `write:mutes`, and work on any status the user can see, as on
  Mastodon. Repeats do nothing more, and a boost's ID stands for the status it boosts, so `muted` is always false on the
  boost itself.
- A mute covers the status's whole thread. When a missing parent arrives and joins two parts of a thread, a mute of
  either part covers all of it.
- A muted thread adds no notifications about its statuses, and unmuting it doesn't bring back what came while it was
  muted. Quotes waiting for the user's review still ask for it, and direct statuses still reach
  `GET /api/v1/conversations`.
- Mutes are private. Nobody else is told, including other servers.
- Unmuting a status the user can no longer see still unmutes its thread, but answers 404.

### Favourites

- `GET /api/v1/favourites` needs `read:favourites`, and pages by when each status was favourited, with the favourites'
  IDs as cursors in the `Link` header, as on Mastodon. Statuses the user can no longer see are left out, so a page can be
  shorter than `limit` and still have more after it. Filters don't hide them, but are given in `filtered`.
- `GET /api/v1/statuses/:id/favourited_by` needs no token for a status anyone can see, and a boost's ID stands for the
  status it boosts. It pages by the favourites' IDs, like `GET /api/v1/favourites`, and leaves out suspended and deleted
  accounts, and those on suspended servers. With a token, it also leaves out accounts the user has blocked, muted, or
  blocked the server of, and those that block the user, as on Mastodon.
- For a status from another server, `favourited_by` only lists this server's accounts, since favourites from elsewhere
  aren't sent here.

### Statuses from other servers

- A status from another server is only stored when it's sent here: when a local account follows its author or is
  addressed by it, or when it's a public or unlisted reply to a stored status of a local account or of one that's
  followed here.
- When a local account starts following someone, up to 40 of their recent public and unlisted statuses are fetched too.
  These are ordered by when they were posted, so a client that's already loaded newer statuses won't be given them as
  new. Other older ones, and replies that arrive before their parent, aren't there.
- Their HTML is sanitized, so it can differ from what the other server shows.
- Their `emojis` are the ones their server listed, which are copied here as their attachments are, and link to their
  server until then.
- Their `media_attachments` are copied here shortly after they arrive, as Mastodon does. Until then, `url` and an
  image's `preview_url` are `/media_proxy/:id/original` and `/media_proxy/:id/small`, which download the file and
  redirect to the copy, as Mastodon's do. One that can't be copied has `url` and an image's `preview_url` the same as
  `remote_url`, and the proxy redirects to it when its server is past its hourly limit or fails.
  - A copy is checked like an upload, so its type is what the file is, and a file that isn't an accepted format is
    `unknown`.
  - Copies are removed after 30 days by default, and their URLs are the proxy's again until it fetches them again.
  - Up to 16 are kept, more than Mastodon shows.
  - One from a server or account whose media an admin rejects is `unknown`, with `url` the same as `remote_url`, and no
    `preview_url`.
- Their `tags` are the hashtags their server listed, and are replaced when they're edited.
- Their `mentions` only list local accounts, and accounts on other servers that are already known here.
- A reply whose parent isn't stored has a null `in_reply_to_id` and `in_reply_to_account_id`.

### Context

`GET /api/v1/statuses/:id/context` only lists statuses stored here.

- With a token, missing parents and replies to a status from another server are fetched in the background, at most
  every 15 minutes for each status, so a later request can list more. Nothing is fetched without a token.
- While a fetch is running, the response has Mastodon's `Mastodon-Async-Refresh` header, always with `retry=5`, and its
  `result_count` counts parents as well as replies.
- It lists up to 40 ancestors, and up to 60 descendants at most 20 replies deep, the limits Mastodon uses without a
  token, whoever asks. Statuses the user can't see are left out after those limits apply, so fewer can be listed.

`GET /api/v1_alpha/async_refreshes/:id` only knows the refreshes of that endpoint.

- A refresh is found for a day after it starts, or until its status's thread is fetched again, and only for a user who
  can see its status.
- It's `finished` once nothing is left to fetch, or after 15 minutes even if some fetches are still waiting.
- `result_count` includes statuses the user can't see.
- A token with `read:statuses` is enough, where Mastodon wants `read`.

### Timelines

- `GET /api/v1/timelines/home` is worked out from the statuses stored here each time, rather than kept as a feed, so it
  never answers `206`, and an account's stored statuses show as soon as it's followed, older ones too.
  - A followed account's reply is only listed when it's to its author, to the user, or to someone they follow, as on
    Mastodon, and one to a status that isn't stored here is left out.
  - Direct messages addressed to the user are included only when they follow the author.
  - The user's own statuses and replies are always listed.
  - Statuses and boosts by members of the user's exclusive lists are left out, as on Mastodon.
  - `local` has no effect, and followed hashtags don't add statuses to it.
  - It lists every boost from the accounts the user follows, even of a status that's already there.
- `GET /api/v1/timelines/public` lists public statuses from other servers too, as on Mastodon. With both `local` and
  `remote`, `local` wins. Boosts aren't listed there, as on Mastodon.
- `GET /api/v1/timelines/tag/:hashtag` lists public statuses with the tag, replies included, from this server and
  others, as on Mastodon, with the same `local`, `remote`, and `only_media` as the public timeline.
  - `any[]`, `all[]`, and `none[]` each use their first 4 names, as on Mastodon.
  - A `:hashtag` that isn't a valid name, or a name in `all[]` that isn't one, gives an empty list.
- `only_media` works on the public and hashtag timelines and on `GET /api/v1/accounts/:id/statuses`, and leaves out
  boosts.

### Tags

- `GET /api/v1/tags/:id` finds any valid tag name, even one that nothing uses, and gets a 404 for an invalid one. Any
  token works, or none.
- `history` counts only public statuses stored here, over the last 7 days.
- Tags can't be followed or featured, so `following` is always false, and is left out without a user token. The
  endpoints for following, unfollowing, featuring, and trending tags, and `GET /api/v1/followed_tags`, aren't
  implemented.

### Boosts

- `POST /api/v1/statuses/:id/reblog` boosts a public or unlisted status, or one of the user's own followers-only
  statuses, whose boost is always followers-only. Any other status gets a 404.
- `visibility` can be `public`, the default, `unlisted`, or `private`, sent as a form, a query parameter, or JSON.
  `direct` gets a 422.
- Boosting a status again returns the boost that's there.
- A boost's ID stands for the status it boosts when it's boosted, favourited, replied to, or asked for its context, and
  `DELETE /api/v1/statuses/:id` with a boost's ID unboosts it.
- `GET /api/v1/statuses/:id/reblogged_by` works like
  [`favourited_by`](#favourites), but only lists public and unlisted boosts, so an author's boost of their own
  followers-only status isn't listed. For a status from another server, it only lists the boosts this server knows of.
- A boost from another server is only stored when it's of a local status, or when a local account follows the booster,
  so a remote account's other boosts aren't listed with its statuses.
- A boosted status that isn't stored yet is fetched first, so the boost can show up a little later than on Mastodon.
- `reblogs_count` only counts the boosts this server knows of.

### Quotes

- `quoted_status_id` quotes a status the user can see, and a boost's ID quotes the status it boosts. One that can't be
  found gets a 404, and one the user isn't allowed to quote a 422.
- A direct status can't be quoted, and a followers-only one only by its author, which makes the quote followers-only
  too.
- A status from before this server supported quotes can only be quoted by its author, until `quote_approval_policy` or
  `quote_others` is set on it. Setting only `quote_others` keeps it `nobody`. A status from another server that
  doesn't say who can quote it can be quoted by anyone, and the quote is accepted straight away, since that server can't
  be asked.
- The text starts with a `<p class="quote-inline">RE: …</p>` link to the quoted status unless it links to it already,
  as on Mastodon.
- `quote_approval_policy` says whose quotes are approved automatically: `public`, `followers`, or `nobody`, meaning
  only the author. It's accepted on a new status, in `PUT /api/v1/statuses/:id`, and in
  `PUT /api/v1/statuses/:id/interaction_policy`. Missing means the account's `source[quote_policy]`, which is
  `public` until it's changed. A followers-only or direct status is always `nobody`.
- `quote_others`, which Mastodon doesn't have, says what happens to everyone else's quotes: `reject`, as on Mastodon,
  or `review`, which waits for the user to approve or reject them with
  [interaction requests](#interaction-requests). It changes nothing for a `public` status. It's accepted in the same
  three places. Missing means the account's `source[quote_others]` on a new status, which is `reject` until it's
  changed, and keeps what it was in an edit. A `public` status doesn't keep it, so changing one to `followers` or
  `nobody` without `quote_others` uses the account's default.
  `PUT /api/v1/statuses/:id/interaction_policy` takes either field alone. Another value gets a 422.
- With `review`, `quote_approval.manual` has `public`, so a client shows `current_user` as `manual` for everyone whose
  quotes aren't approved automatically. With `followers`, `automatic` has `followers`, and with `nobody` it's empty.
  Changing either setting doesn't change the quotes approved or waiting already.
- `PUT /api/v1/statuses/:id` can change what a status quotes, which Mastodon ignores. A missing `quoted_status_id`, or
  the one it quotes already, keeps the quote and its approval, and a null or empty one removes it. Another status is
  quoted as it would be by a new status, with the same 404 and 422, and its quote is approved again. A status that
  isn't followers-only or direct can't quote a followers-only one, a local-only one can't quote one from another
  server, and one that isn't local-only can't quote a local-only one, since an edit can't change its visibility: each
  gets a 422, as does quoting itself. The quoted author of a quote that's removed loses its notification.
- A quote of a status on another server is `pending` until that server approves it, and `rejected` if it refuses.
  A quote of a local status whose quotes need approval is `pending` until its author decides.
- `POST /api/v1/statuses/:id/quote/request`, which Mastodon doesn't have, asks again for approval of what one of the
  user's statuses quotes, when its `quote.state` is `pending`, `rejected`, or `revoked`, and returns the status. A quote
  of a local status is decided again by its author's policy. One of a status on another server gets a new request, and
  is `pending` again. A quote can be asked for once an hour. One that's `accepted`, was asked for less than an hour
  ago, or whose author no longer allows the user to quote it, gets a 422, and a status that isn't the user's or doesn't
  quote anything gets a 404.
- A quote from another server is `pending` until this server has checked its approval, which can take a moment. One
  from a server that can't ask for approval is `accepted`, unless it quotes a local status whose policy wouldn't allow
  it.
- `quote` is a Quote at the top level, including in `reblog`, and a ShallowQuote inside `quoted_status`.
- `quoted_status` is null when the user can't see the quoted status, with the state `unauthorized`, and it's `deleted`
  once the quoted status is.
- `quote_approval.automatic` and `manual` can have `unsupported_policy` for a status from another server, and
  `current_user` is `unknown` without a token.
- `quotes_count` only counts the accepted quotes this server knows of that anyone can see.
- `GET /api/v1/statuses/:id/quotes` lists the accepted quotes that the user can see, of any status they can see, not
  only their own.
- `POST /api/v1/statuses/:id/quotes/:quoting_status_id/revoke` revokes any quote of the user's status, accepted or not,
  and gets a 404 for a status that doesn't quote it. Revoking a quote from a server that can't ask for approval only
  hides it here.
- Blocking an account revokes its accepted and pending quotes of the user's statuses, which Mastodon doesn't do.
  Unblocking it doesn't bring them back.
- A quote that was rejected or revoked can be asked for again, by its author here or by its server, once an hour, and
  then waits for the quoted author to review it, even if their policy approves quotes automatically.

### Interaction requests

Quotes that need approval are reviewed with GoToSocial's `/api/v1/interaction_requests`, which Mastodon doesn't have.

- `GET /api/v1/interaction_requests` lists the quotes of the user's statuses that wait for them, newest first, and takes
  `status_id`, `limit`, `max_id`, `since_id`, and `min_id`. Pages are by when the quote was posted, and default to 20
  and allow up to 40. It needs `read:notifications`.
- Only quotes are approved by hand, so each request's `type` is `quote`, which GoToSocial doesn't have. `quotes=false`
  lists nothing, and `favourites`, `replies`, and `reblogs` are accepted and change nothing.
- A request's `id` is the quote's, `account` is who quoted, `status` is the user's status, and `quote`, which
  GoToSocial doesn't have, is the quote. `created_at` is when the quote was last asked for. `accepted_at` or `rejected_at` is
  when it was last decided, and neither is there while it's waiting. A quote decided before this server recorded when
  has the time it was asked for, or posted.
- `GET /api/v1/interaction_requests/:id` shows any quote of the user's statuses, waiting or not.
- `POST /api/v1/interaction_requests/:id/authorize` and `/reject` need `write:statuses` and return the request. A quote
  the user rejected can be approved later, and an approved one is returned as it is. To withdraw an approval, revoke the
  quote. Either gets a 404 for a quote of someone else's status, and approving one gets a 404 if the status's policy no
  longer allows its author to quote it.
- A user is notified with a `pending.quote` notification when a quote waits for them, and again when it's asked for
  again. It's removed once the quote is decided, and an approved quote gets a `quote` notification.

## Announcements

- Admins write announcements on the `/admin/announcements` page. `GET /api/v1/announcements` lists the published ones,
  dismissed ones included, and needs a user's token with any scope, as on Mastodon.
- Their IDs are UUIDs rather than numbers.
- `content` is HTML made from plain text, with links and hashtags linked, and mentions left as text. `mentions` and
  `statuses` are always empty.
- There's no scheduled publishing, so `published_at` is when an admin last published it.
- Reactions take one Unicode emoji or the shortcode of one of this server's custom emoji, as on Mastodon, and anything
  else gets a 422. An announcement can have up to 8 different emoji, and a 9th gets a 422.
- `DELETE /api/v1/announcements/:id/reactions/:name` for an emoji the user hasn't reacted with returns `{}`, rather than
  Mastodon's 404.

## Emoji reactions

Follow [Pleroma's API](https://docs.akkoma.dev/stable/development/API/pleroma_api/#emoji-reactions), since Mastodon has
none.

- `PUT /api/v1/pleroma/statuses/:id/reactions/:emoji` reacts to a status, and `DELETE` with the same path removes the
  reaction. Both need `write:favourites` or `write:statuses`, return the status, and do nothing more when repeated.
- A boost's ID stands for the status it boosts.
- `GET /api/v1/pleroma/statuses/:id/reactions`, and the same with `/:emoji`, list reactions with their `accounts`, and
  need no token for a status anyone can see.
- `:emoji` is one Unicode emoji, the shortcode of one of this server's custom emoji, with or without colons, or
  `shortcode@domain` for another server's emoji that this server has from a post or a reaction. Anything else,
  including text and two emoji, gets a 422.
- An account can react to a status with up to 20 different emoji, and a 21st gets a 422.
- A reaction isn't a favourite, so it doesn't change `favourited` or `favourites_count`.
- A status's reactions are in `pleroma.emoji_reactions`, in the order each emoji was first used. Each has `name`,
  `count`, and `me`, and `url` and `static_url` for a custom emoji. A custom emoji's `name` is its shortcode, followed
  by `@` and its server's domain for another server's.
- A listing's `accounts` has up to 40 of each emoji's accounts, the most recent first.
- Reactions from other servers are only known for this server's statuses, so another server's status only shows this
  server's accounts' reactions.
- The author gets a `pleroma:emoji_reaction` notification, with the emoji in `emoji`, and a custom emoji's image in
  `emoji_url`. In grouped notifications, each reaction is a group of its own, and has neither field.
- Reactions aren't streamed, as favourites aren't.
- NodeInfo's `metadata.features` lists `pleroma_emoji_reactions` and `custom_emoji_reactions`, which clients look for.

## Polls

Follow [Mastodon's polls API](https://docs.joinmastodon.org/methods/polls/).

- A poll's `id` is its status's.
- A new poll can have 2 to 4 options of up to 50 characters, which have to be different and not blank, and be open for
  300 to 2629746 seconds, as on Mastodon. `GET /api/v1/instance` gives these limits. `expires_in` is required.
- In `PUT /api/v1/statuses/:id`, a missing `poll` keeps the status's poll, and a null or empty one removes it.
  - Changing its `options` or `multiple` makes it a new poll, as on Mastodon, which loses its votes and is open for
    `expires_in` from then.
  - Otherwise the poll keeps its votes and when it closes, and only `hide_totals` changes.
- `voters_count` is null unless more than one option can be chosen.
- While a poll with `hide_totals` is open, each option's `votes_count` is null, even for its author, and `votes_count`
  still has the total.
- `POST /api/v1/polls/:id/votes` takes `choices[]` as a form, a query parameter, or JSON. A choice that isn't an
  option, one given twice, more than one in a poll that allows one, or none at all, gets a 422.
- A poll from another server has the counts its server last sent, and a vote from here is added to them until its server
  sends new ones. `GET /api/v1/polls/:id` asks that server for new counts in the background when they're more than 5
  minutes old, so a later request has them. It also asks once the poll ends.

## Scheduled statuses

Follow [Mastodon's scheduled statuses API](https://docs.joinmastodon.org/methods/scheduled_statuses/), with the
`read:statuses` and `write:statuses` scopes.

- `scheduled_at` in `POST /api/v1/statuses` has to be an ISO 8601 date and time with an offset, such as
  `2030-01-01T09:00:00Z`. It has to be more than a minute ahead, rather than Mastodon's 5 minutes. A user can have up
  to 300 scheduled, with at most 25 on one day in UTC, as on Mastodon. Anything else gets a 422.
- A `scheduled_at` in the past posts the status now, and the response is a `Status`, as on Mastodon.
- A scheduled status is checked when it's scheduled, as if it were posted then, and gets the same errors. It's checked
  again when it's posted, within about a minute of its time.
- A scheduled status that can no longer be posted is dropped, as on Mastodon, with no notification. That happens when
  what it replies to or quotes has been deleted or can't be seen, or it's no longer allowed to quote. Its media becomes
  unattached uploads for an hour by default, as a deleted status's does.
- While the user is suspended, their scheduled statuses aren't posted. Any that came due in the meantime are posted
  once the suspension ends.
- If posting one fails for some other reason, such as the database being unavailable, it's tried again later, waiting
  longer each time, and dropped after about an hour and a quarter.
- Its media is held for it, and doesn't expire or count toward the limit on uploads waiting to be posted. A user can
  have at most 100 uploads held, and a status that would hold more gets a 422. Held media can't be attached to another
  status, and `DELETE /api/v1/media/:id` gets a 422 for it. `GET` and `PUT /api/v1/media/:id` don't
  find it.
- `DELETE /api/v1/scheduled_statuses/:id` makes its media unattached uploads for an hour by default, so that it can be
  posted another way.
- `PUT /api/v1/scheduled_statuses/:id` only changes `scheduled_at`, as on Mastodon, which is required, and has the same
  limits. A time in the past gets a 422 there, as on Mastodon.
- `params` has the fields the request gave, and also `quoted_status_id`, `quote_approval_policy`, `local_only`,
  `content_type`, and `quote_others`. `idempotency`, `scheduled_at`, and `application_id` are always null, and a poll's `expires_in` is a
  string, as on Mastodon.
- When it's posted, its `created_at` is when it was posted, and a poll's `expires_in` counts from then.

## Uploads

Follow [Mastodon's media API](https://docs.joinmastodon.org/methods/media/), with the `write:media` scope.

- `POST /api/v2/media` and `POST /api/v1/media` process the file before they answer, so they always answer 200 with a
  `url`, never 202.
- Uploads can be PNG, JPEG, GIF, or WebP images, MP4, MOV, or WebM video, or MP3, M4A, Ogg, FLAC, or WAV audio. A file's
  type is worked out from its content, and anything else gets a 422, whatever it's sent as.
- Images are re-encoded as WebP, without their metadata, and scaled down to fit 3840 × 2160 pixels in area. Their
  `preview_url` fits 640 × 360.
- An animated GIF becomes an animated WebP of type `image`, never `gifv`.
- An image with more than 30 million pixels gets a 422, and so does an animation with more than 300 frames, or with
  more than 25 million pixels in all its frames together, such as 100 frames of 500 × 500.
- Video and audio are kept as they were uploaded, so `meta` has no `original` for them, and they have no `preview_url`
  unless a `thumbnail` is uploaded with them. A `thumbnail` with an image gets a 422, and so does one in
  `PUT /api/v1/media/:id`.
- Images, video, and audio each have their own size limit, 16 MB, 100 MB, and 40 MB by default, and a file over its
  limit gets a 422. A request that's larger than the largest file and an image `thumbnail` together, plus 4 MB, gets a
  413 before it's read.
- A user can have at most 20 uploads waiting to be posted by default, and one more gets a 422. An upload that isn't
  posted within a day, by default, is deleted.
- `blurhash`, `preview_remote_url`, and `text_url` are always null.
- `GET` and `PUT /api/v1/media/:id` only find uploads that aren't attached to a status yet, as on Mastodon. In a `PUT`,
  a missing `description` or `focus` keeps what the upload has, and an empty `description` removes it.
- `DELETE /api/v1/media/:id` gets a 422 for an upload that's attached to a status.

## Notifications

Follow [Mastodon's notifications API](https://docs.joinmastodon.org/methods/notifications/) and
[grouped notifications API](https://docs.joinmastodon.org/methods/grouped_notifications/).

### Endpoints

- V1: `GET /api/v1/notifications`, `GET /api/v1/notifications/:id`, `POST /api/v1/notifications/clear`,
  `POST /api/v1/notifications/:id/dismiss`, and `GET /api/v1/notifications/unread_count`.
- V2: `GET /api/v2/notifications`, `GET /api/v2/notifications/:group_key`,
  `GET /api/v2/notifications/:group_key/accounts`, `POST /api/v2/notifications/:group_key/dismiss`, and
  `GET /api/v2/notifications/unread_count`. V1's clear endpoint clears notifications from both views.
- Notification policies and notification requests aren't implemented. `include_filtered` has no effect, and
  `supported_types[]` has no effect because generated types have no fallback representations.
- Push subscriptions aren't implemented, but [streaming](#streaming) is.

### Types

Only these types are generated: `mention`, `status`, `follow`, `follow_request`, `favourite`, `reblog`, `quote`,
`quoted_update`, `update`, `poll`, `move`, `admin.report`, `pleroma:emoji_reaction`, which [Emoji reactions](#emoji-reactions) describes, and
`pending.quote`, which [interaction requests](#interaction-requests) describes.

- A `status` arrives, as on Mastodon, for a new post by an account the user follows with `notify`, if the post reaches
  their home timeline. Replies to others, and posts in languages the follow leaves out, don't.
- A `quote` arrives once the quote is accepted, and `quoted_update` once for each edit of a status that a user quoted.
  `update` arrives, as on Mastodon, once for each edit of a status that a user boosted, and goes away if they unboost it.
- A `poll` arrives within about a minute of a poll ending, for its author if they're a local user, and for each local
  user who voted in it. Its `account` is the poll's author.
- `admin.report` goes to each admin, except one who filed the report. Only admins see them, so an account that stops
  being an admin stops seeing them, in lists and counts, until it's an admin again. Its `account` is the reporter,
  which for a report from another server is whoever sent it there, usually that server's instance actor. Blocking or
  muting the reporter doesn't hide it.
- A `move` goes to each local follower that was moved when an account moved. Its `account` is the old account, and
  `target` the new one, as on Mastodon. A follower who couldn't be moved, because the new account blocks them, gets
  none. Grouped notifications don't include `target`, so clients use the sample account's `moved`.
- A `follow_request` notification is removed once the request is approved, refused, or withdrawn, as on Mastodon.
  Approving it adds a `follow` notification from the new follower, which Mastodon doesn't, including when requests are
  approved because the account stopped approving followers by hand, or approves requests from accounts it follows.
- Notifications of subscribed accounts' posts, and `admin.sign_up`, await their features.
- Notifications start with new activity after upgrading, so historical mentions and follows don't generate any.
  Silent mentions and self-mentions don't notify.
- An update may appear shortly after the activity that caused it is stored.

### Paging and filtering

- Pages default to 40 notifications, and at most 80. Unread counts default to a cap of 100, and at most 1000.
- V2 groups follow events within a span of up to twelve hours, and favourites and reblogs by status. Mentions remain
  individual groups.
- `types[]`, `exclude_types[]`, and `account_id` filter both lists and counts, and exclusions win over inclusions.

### What's hidden

- Notifications from suspended, deleted, or domain-blocked senders are omitted, as are those about deleted statuses.
  So are those from senders the user blocks, mutes with notifications, or has blocked the server of, and from senders
  that block the user.
- Blocking removes the notifications between the two accounts. Nothing new is added from a muted sender, so unmuting
  brings back only what came before the mute.
- Notifications aren't suppressed by a per-domain quota.
- Reads don't mark notifications as read, and dismissing a conversation doesn't dismiss its notifications.

## Conversations

Follow [Mastodon's conversations API](https://docs.joinmastodon.org/methods/conversations/).

- `GET /api/v1/conversations`, `DELETE /api/v1/conversations/:id`, and `POST /api/v1/conversations/:id/read` are
  implemented.
- Pages default to 20 conversations, and at most 40. Follow the `Link` header, because cursors are latest-status IDs,
  not conversation IDs.
- A conversation is a thread of direct statuses for one participant set. A new top-level message starts a separate
  conversation even with the same participants.
- Only stored statuses are included, and missing remote parents are never fetched. Thread parts join when a missing
  parent arrives, which can merge provisional conversation entries.
- Existing direct messages appear as read conversations after upgrading. New incoming messages make a conversation
  unread, and sending a message or marking it read clears that.
- Deleting a conversation hides it only for the user, until a newer message arrives, and doesn't delete statuses.
- Notification read markers are independent.
- As in Mastodon, silent mentions don't create recipient conversations.
- Suspended, deleted, and domain-blocked accounts are omitted from conversation accounts and message summaries.
  A conversation's latest status leaves out accounts hidden from the user's notifications, and a direct status from one
  of them doesn't start a conversation.

## Markers

Follow [Mastodon's markers API](https://docs.joinmastodon.org/methods/markers/).

- `GET /api/v1/markers` and `POST /api/v1/markers` support `home` and `notifications` with Mastodon's scopes.
- Reads without `timeline[]` return `{}`. Writes accept JSON, form, or multipart bodies and return marker versions and
  timestamps.
- Saving a notifications marker controls both notification unread counts without dismissing notifications.
- Positions and paging cursors must be UUIDs, and invalid values get a 422. Concurrent marker updates can get a 409.
  Missing or other users' notification and conversation IDs get a 404.
- These endpoints require user tokens, and an app token gets a 422.

## Filters

Follow [Mastodon's filters API](https://docs.joinmastodon.org/methods/filters/), in both its v2 form at
`/api/v2/filters` and its deprecated v1 form at `/api/v1/filters`, which some clients still use.

### Matching

- Statuses have Mastodon's `filtered`, listing the user's unexpired filters that they match in any context. It's left
  out without a user token.
- A boost matches by the status it boosts. The user's own statuses, and boosts of them, never match, as Mastodon's own
  app treats them, so notifications about them aren't hidden either.
- Keywords are matched without regard to case, in a status's content warning, text, poll options, and media
  descriptions. For a status from another server, its text is what its HTML shows.
- A whole-word keyword only needs a word boundary at an end that's a letter, digit, or underscore, so `#tag` doesn't
  match `#tagged` but does match `#tag!`.
- A filter whose action is `hide`, or a v1 filter that's `irreversible`, leaves what it matches out of the lists in its
  contexts: the home timeline for `home`, notifications about those statuses for `notifications`, the public and
  hashtag timelines for `public`, a status's context for `thread`, an account's statuses for `account`, and the streams
  of those timelines.
  - Pages can then have fewer statuses than `limit`, but their `Link` headers still work.
  - A status that's fetched by itself is always given, with `filtered`.
  - `warn` and `blur` filters are left to clients.

### Limits and behavior

- A user can have up to 100 filters, each with up to 100 keywords and 1000 statuses. Titles can be up to 256 characters,
  and keywords up to 512. Mastodon has no limit on how many there are, and going over these gets a 422.
- Adding a status to a filter needs a status the user can see, or the answer is a 404. A status that's deleted is taken
  out of the filters it was in.
- Where more than one copy of the server runs, a change to a user's filters can take up to a minute to apply to what the
  other copies list.
- `keywords_attributes` can be a list or an object keyed by position in JSON. In a form, it can be
  `keywords_attributes[][keyword]`, where the nth value of each field makes the nth keyword, or numbered, as
  `keywords_attributes[0][keyword]`. A keyword's `id` that isn't one of the filter's gets a 404, and nothing changes.
- A new keyword's `whole_word` defaults to true in v2, as on Mastodon, and to false in v1, as Mastodon documents.

### v1

- Each v1 filter is one keyword of a v2 filter, with the keyword's ID. Creating one makes a filter named after the
  phrase.
- The context, `irreversible`, and expiry of a filter with more than one keyword can't be changed through v1, as on
  Mastodon, but unlike Mastodon its keywords' phrases and `whole_word` can. `phrase` and `context` aren't required when
  updating.
- A `blur` filter is shown as not `irreversible`, and making it not `irreversible` leaves it as it is.
- Deleting a v1 filter removes its keyword, and also its v2 filter if nothing else is left in it, where Mastodon keeps
  an empty filter.

## Lists

Follow [Mastodon's lists API](https://docs.joinmastodon.org/methods/lists/), with
`GET /api/v1/timelines/list/:list_id` and `GET /api/v1/accounts/:id/lists`.

- A user can have up to 50 lists, as on Mastodon, and a title can be up to 256 characters, which Mastodon doesn't limit.
  Going over either gets a 422. Lists are listed oldest first.
- An account can be added once the user follows it or has asked to, as on Mastodon.
  - Adding one they don't follow, or one that's suspended, deleted, or unknown, gets a 404, and one that's already in
    the list a 422. Either way, none of the accounts are added.
  - Unfollowing or blocking an account takes it out of the user's lists, as on Mastodon.
  - Removing accounts with an ID that isn't a UUID gets a 404, where Mastodon ignores it.
- `GET /api/v1/lists/:id/accounts` pages by when each account was added, newest first, and the cursors are the IDs in
  the `Link` header, not account IDs. `limit=0` lists all of them, as on Mastodon. Suspended and deleted accounts are
  left out.
- A list's timeline is worked out from the statuses stored here each time, as the home timeline is, so a newly added
  member's older statuses show straight away.
  - It lists what the home timeline would of its members' statuses and boosts, apart from direct statuses, and nothing
    from a member whose follow request is still waiting.
  - A member's reply to themselves or to the user is always listed, and other replies by `replies_policy`.
  - Filters for `home` apply to it, as on Mastodon.
- `exclusive` leaves the members' statuses and boosts out of the home timeline and the `user` stream.

## Streaming

Follows [Mastodon's streaming API](https://docs.joinmastodon.org/methods/streaming/), over a WebSocket at
`/api/v1/streaming`, which `urls.streaming_api` points to, and as server-sent events at its other paths.

### Events

- There are no `announcement`, `announcement.reaction`, `announcement.delete`, `notifications_merged`, or
  `encrypted_message` events.
- `filters_changed` is sent on `user`, as on Mastodon, with no payload: the WebSocket message has no `payload`, and the
  event stream's `data` is empty.
- An edit that makes a status match a filter that hides it there is sent as a `delete`. `delete` goes to every stream
  that carries statuses, whether or not it carried that status.
- Events can arrive out of order, or more than once, shortly after what they're about happens.

### Streams

- A stream carries what its timeline would list: `user` what the home timeline would, `list` what the list's timeline
  would, and `public` and `hashtag` what the public and hashtag timelines would. That includes leaving out what the user
  has hidden or their filters hide. A status from another server only arrives once it's stored here.
- `public`, `public:local`, `public:remote`, their `:media` forms, `hashtag`, and `hashtag:local` work without a token,
  as their timelines do. With one, they need `read:statuses`.
- `user`, `direct`, and `list` need `read:statuses`, as on Mastodon, though a list's timeline needs `read:lists`.
- `user:notification` needs `read:notifications`, with a user token, and `user` only has notifications if the token can
  read them.

### Errors and limits

- The token can be in the `Authorization` header, the `access_token` query parameter, or the `Sec-WebSocket-Protocol`,
  but the last two only work for streaming.
- A WebSocket can have up to 50 subscriptions, and more get an error.
- A WebSocket message that can't be followed gets `{"error": …, "status": 400}`, or 401 or 403 for a stream that needs a
  token or scope it doesn't have, and the connection stays open. A stream given in the URL that can't be subscribed to
  gets the error, and the connection is closed.
- An event stream with a missing or invalid `tag`, or a missing `list`, gets a 400, and one with an app token for a
  stream that needs a user gets a 422.
- A `list` that isn't the user's, or doesn't exist, gets a 401 with "Not authorized to stream this list", as on
  Mastodon, on a WebSocket too.
- The server limits how many streaming connections can be open at once, from all clients together. Past that, a
  WebSocket is closed with code 1013, and an event stream gets a 503.
- A connection is closed within about 30 seconds of its token being revoked or expiring, or its account being
  suspended.

## Accounts and follows

Follow [Mastodon's accounts API](https://docs.joinmastodon.org/methods/accounts/) and
[search API](https://docs.joinmastodon.org/methods/search/).

### Endpoints

Only these exist:

- `GET /api/v1/accounts/:id`, `verify_credentials`, `lookup`, `relationships`, `familiar_followers`, `:id/statuses`,
  `:id/followers`, and `:id/following`
- `POST /api/v1/accounts/:id/follow`, `/unfollow`, `/remove_from_followers`, `/block`, `/unblock`, `/mute`, `/unmute`,
  `/note`, `/endorse`, `/unendorse`, `/pin`, and `/unpin`
- `PATCH /api/v1/accounts/update_credentials`
- `DELETE /api/v1/profile/avatar` and `/api/v1/profile/header`
- `GET /api/v1/follow_requests`, and `POST /api/v1/follow_requests/:account_id/authorize` and `/reject`
- `GET /api/v1/blocks`, `/api/v1/mutes`, and `/api/v1/endorsements`
- `GET`, `POST`, and `DELETE /api/v1/domain_blocks`
- `GET /api/v2/search`

### Accounts on other servers

- An account on another server shows its profile as of when this server last fetched it, so a change can take a while
  to show if its server doesn't announce it.
- One with no name has its username as `display_name`, where Mastodon sends an empty one.
- Its `avatar` and `header`, and their `_static` forms, are `/media_proxy/accounts/:id/…` URLs until they're
  downloaded, which download the image and redirect to the copy. They're links to its server, possibly on another host,
  if they can't be downloaded or an admin rejects its server's or its media.
- A `fields` entry containing only an HTTPS URL can get a `verified_at` after the profile is fetched. If the URL is an
  HTML link, its text has to match its destination. The linked page has to link back with `rel="me"` to the actor URI
  or to the profile URL on the actor's origin, matching the case of its path and query.
- `followers_count` and `following_count` only count accounts on this server, and `hide_collections` is null.
- `discoverable` is always false. `indexable` is what the actor document said when this server last fetched it.
- `GET /api/v1/accounts/:id/statuses` only lists its statuses stored here.
- `moved` is the account its actor document or `Move` last said it moved to. It's left out if that account isn't stored
  here, or is suspended, deleted, or on a blocked server.

### Profile

- `update_credentials` can change `display_name`, `note`, `fields_attributes`, `avatar`, `header`, `locked`,
  `indexable`, `hide_collections`, `source[quote_policy]`, and `source[quote_others]`, which Mastodon doesn't
  have, and which `source` has too.
  - `bot`, `discoverable`, and the rest of `source` are accepted only with the values the account already has: false,
    and `public` for `source[privacy]`.
  - Any other value for them, or any other field with a value, gets a 422 naming it, and nothing is changed.
  - Empty values are ignored, except that an empty `display_name` or `note` clears it, as on Mastodon.
- `GET /api/v1/preferences` needs `read:accounts`, as on Mastodon. `posting:default:quote_policy` is
  `source[quote_policy]`. The other preferences can't be changed yet, so they have the values a new account would have
  on Mastodon: `public`, not sensitive, no language (`null`), media hidden only when sensitive, content warnings
  collapsed, and GIFs not autoplayed.
- `fields_attributes` replaces all of the fields. It can be a list or an object keyed by position, in JSON or in a form
  (`fields_attributes[0][name]` or `fields_attributes[][name]`). Fields with an empty name and value are left out.
- How many fields there can be is set by the admin, and defaults to 4. `GET /api/v1/instance` reports it in
  `configuration.accounts.max_profile_fields`, which Mastodon doesn't send.
- A field whose value is only an `https` URL gets a `verified_at` once that page links back to the account with
  `rel="me"`. This server checks when the field is saved, and only follows 3 redirects. A field keeps its `verified_at`
  while its value stays the same.
- `avatar` and `header` can only be uploaded in a multipart form, not as data in JSON. They have to be a PNG, JPEG, GIF,
  or WebP, 8 MB at most by default.
- They're always re-encoded as WebP, which removes their metadata, and cropped to 400 × 400 and 1500 × 500. Animated
  ones stay animated, and `avatar_static` and `header_static` are their first frame.
- Turning `locked` off approves the requests that are waiting, as on Mastodon, but in the background, so they can still
  be listed just after the response. Turning it on keeps the account's followers.

### Following

- Following an account on another server returns `requested: true` until that server accepts, even if it doesn't
  approve followers by hand.
- Following a `locked` account here that follows you can return `following: true` straight away, when its user has
  chosen at `/settings` to approve requests from people they follow. Clients can't read or change that choice.
- Following yourself, or an account on a server the admin has blocked, gets a 422. Following an account when either
  blocks the other, or on a server the user has blocked, gets a 403, as on Mastodon.
- `reblogs` and `languages` apply to the user's lists as well as their home timeline, as on Mastodon. A boost counts as
  being in the language of the post it boosts, and a post whose language isn't known is always shown. Languages match
  ignoring case, and `pt` also matches regional codes such as `pt-BR`.
- `POST /api/v1/accounts/:id/endorse` gets a 422 unless the user follows the account, as on Mastodon, and an endorsement
  ends when the follow does, including when the account moves. `/pin` and `/unpin` are the same as `/endorse` and
  `/unendorse`. Endorsements aren't sent to other servers, and `GET /api/v1/accounts/:id/endorsements` doesn't exist.
- `GET /api/v1/endorsements` pages by when each endorsement was made, like `/api/v1/mutes`, and leaves out suspended and
  deleted accounts.
- `POST /api/v1/accounts/:id/note` reads `comment` from the query, a form, or JSON, and gets a 422 if it isn't text, is
  longer than 2,000 characters, or has a NUL character or an unpaired surrogate.
- `GET /api/v1/follow_requests` pages by the request, like `:id/followers`, and leaves out suspended and deleted
  accounts. `authorize` and `reject` get a 404 when that account has no request waiting.
- `:id/followers` and `:id/following` page by the follow, so a cursor from a follow that's since been removed gives an
  empty page. If the account hides them, anyone else gets an empty list.
- `GET /api/v1/accounts/familiar_followers` lists the most recent follow first, and gives an empty list for an account
  that hides its followers, unless it's the user's own. For an account on another server, it only knows of followers on
  this server.
- A suspended account gets a 404 from `GET /api/v1/accounts/:id` and the lists, and is left out of `relationships`,
  even with `with_suspended`, and of followers and following. An account can still be unfollowed, or removed from
  followers, while it's suspended.
- `GET /api/v1/accounts/:id/statuses?pinned=true` lists the pins the viewer can see, the most recently pinned first,
  with no `Link` header, since they fit in one page. Only `limit` applies, and `max_id`, `since_id`, or `min_id` gets an
  empty list. An account on another server has the pins its server has told this one about, which are only kept for
  statuses stored here, and at most 40 of them.

### Blocks and mutes

- Blocking or muting yourself gets a 422, and so does a mute `duration` that's negative or not a whole number of
  seconds. `duration` can be up to about 100 years. Muting an account already muted replaces the mute's `notifications`
  and `duration`.
- `GET /api/v1/blocks` and `/api/v1/mutes` page by when each block or mute was made, like `:id/followers`, and leave out
  suspended and deleted accounts. A mute that's changed counts as made then. Expired mutes are left out of the list,
  and stop hiding anything as soon as they expire.
- `/api/v1/domain_blocks` blocks a server's subdomains too, where Mastodon blocks only the domain given.
  - A domain is read as an admin's [domain block](#admin-domain-blocks) is, so one with a wildcard, a port, or other
    parts of a URL gets a 422, as do IP addresses, a domain with only one label, and this server's own domain.
  - `domain` can be in the query, a form, or JSON, for `DELETE` too.
  - The list pages by when each block was made, defaulting to 100 and at most 200, with cursors in the `Link` header.
- What's hidden by blocks, mutes, and server blocks follows Mastodon: the home, public, and hashtag timelines leave out
  statuses by those accounts and boosts of them, and the home timeline also leaves out replies to them. Threads leave
  them out, apart from the status asked for.
- An account that blocks the user, or the user's server, shows them no statuses, and its statuses get a 404.
- Unlike on Mastodon, the home timeline still shows statuses that only mention those accounts, and searching for
  accounts and hashtags isn't filtered. Searching for statuses is.

### Search

- `lookup` and `search` only ask another server about an account, or `search` about a status, for a signed-in user, and
  `search` only when `resolve` is true. Each user can make 30 of those lookups a minute, and then gets a 429.
- `search` finds a query that's a `username@domain` as that one account, and an `https` URL as the one account or status
  there, and nothing else. Any other query matches the start of the usernames this server knows.
  - A status is found by its URI or by the page its server gave, such as Mastodon's `https://example.com/@alice/1`.
  - A profile page only finds an account with `resolve`.
  - Statuses at a URL are only found for a signed-in user who can see them, even when they block or mute the author, as
    a status's own thread shows it.
  - With `resolve`, a status from another server that isn't stored is fetched, and only stored and found if it's public
    or unlisted. A status that's stored already isn't refreshed.
- Only the first 200 matches can be paged through with `offset`, and an `offset` past them returns no accounts.
  `following` and `exclude_unreviewed` are ignored.
- `search` finds hashtags by the start of their name, from those that public statuses stored here use, most used first.
  A query that's a valid name, with or without its `#`, also finds that tag when nothing uses it, as the first result,
  as on Mastodon. Only the first 200 can be paged through with `offset`.
- `search` only finds statuses for a signed-in user. As on Mastodon, those are the statuses they wrote, favourited,
  bookmarked, or are mentioned in, and public statuses by accounts whose `indexable` is true, newest first. It only
  finds what the user can see, and leaves out accounts they block or mute. Unlike Mastodon:
  - It matches whole words of a status's text and content warning, in any language, with no stemming, so `cats` doesn't
    find `cat`. Words in quotes are a phrase, `OR` goes between alternatives, and `-` before a word leaves out statuses
    that have it. Mastodon's operators, such as `from:`, `has:`, and `before:`, aren't supported, and are read as words.
  - Poll options and media descriptions aren't searched.
  - A `username@domain` query finds no statuses, and an `https` query finds only the status at that URL.
  - The user's filters don't leave statuses out, but are given in `filtered`.
  - Only the first 200 statuses can be paged through with `offset`, and `max_id` and `min_id` page through the rest.
    They don't apply to accounts and hashtags. An `account_id` that isn't a valid ID finds no statuses.
  - An account on another server is only indexable once this server has fetched its actor since `indexable` was set.

## Reports

Follow [Mastodon's reports API](https://docs.joinmastodon.org/methods/reports/), with the `write:reports` scope.

- Reports can't cite the server's rules yet, so a `category` of `violation`, or any `rule_ids`, gets a 422. `rule_ids`
  in a report is always null.
- A status in `status_ids` that isn't the account's, or that the user can't see, gets a 404 rather than being left out.
  A report can include up to 100 statuses, and more get a 422.
- `comment` can be up to 1000 characters, as on Mastodon. Reporting your own account gets a 422.
- `forward` only sends the report to the reported account's server, and `forward_to_domains` is ignored. `forwarded` is
  only `true` when the account is on another server.
- A status that's deleted after it's reported goes from the report, and from its `status_ids`.

## Admin reports

Follow [Mastodon's admin API](https://docs.joinmastodon.org/methods/admin/reports/), under `/api/v1/admin/reports`, with
the `admin:read:reports` and `admin:write:reports` scopes.

- `GET /api/v1/admin/reports` only filters by `resolved`, `account_id`, and `target_account_id`, and pages with
  `max_id`, `since_id`, `min_id`, and `limit`, which defaults to 100 and is at most 200.
- `statuses` has every reported status, even followers-only and direct ones, since whoever reported them shared them
  with admins. `rules` is always empty.
- `PUT /api/v1/admin/reports/:id` only changes `category`, which has to be `spam`, `legal`, or `other`. `violation` and
  `rule_ids` get a 422.
- Resolving a report doesn't do anything to the account, so suspend it through the accounts API. Resolving a report
  that's resolved already keeps who resolved it first.
- A report from another server has the server's actor, usually its instance actor, as `account`.
- A report is removed when the account it's about is deleted, or when it's by or about an account on a server that's
  been blocked.

## Admin domain blocks

Follow [Mastodon's admin API](https://docs.joinmastodon.org/methods/admin/domain_blocks/), under
`/api/v1/admin/domain_blocks`, with the `admin:read:domain_blocks` and `admin:write:domain_blocks` scopes.

- `severity` is required when creating a block, and has to be `suspend` or `noop`. Mastodon's default is `silence`,
  which isn't supported, and gets a 422 here rather than a block that does more or less than an admin meant.
- `reject_media` works on a `noop` block. A `suspend` block always rejects media, and is returned with `reject_media`
  and `reject_reports` as `true`.
- A `noop` block's `reject_reports` is always `false`, since a limited server's reports are accepted.
- `obfuscate` is always `false`, and `public_comment` null. Any values sent for `reject_reports`, `obfuscate`, and
  `public_comment` are ignored.
- `PUT` changes only what it's sent, as on Mastodon, and an empty `private_comment` removes it. Changing a block to
  `suspend`, or turning on `reject_media`, removes what that block removes, and changing it back doesn't bring that
  back.
- A domain with a wildcard, a port, or a URL's other parts is refused, as are IP addresses, a domain with only one label
  such as `com`, and this server's own domain or one above it. Blocks always cover subdomains and every port.
- A Unicode domain that converting would change, such as `faß.example`, has to be sent in its `xn--` form.
- `private_comment` can be up to 1000 characters.
- A domain that a broader block already covers gets a 422 with `existing_domain_block`, like one that's already blocked,
  unless the new block does more, such as suspending a subdomain of a `noop` block.

## Admin accounts

Follow [Mastodon's admin API](https://docs.joinmastodon.org/methods/admin/accounts/), under `/api/v1/admin/accounts`,
with the `admin:read:accounts` and `admin:write:accounts` scopes.

- Only `GET /api/v1/admin/accounts` and `/:id`, `POST /:id/action`, `POST /:id/unsuspend`, and `DELETE /:id` exist.
  There's no `/api/v2/admin/accounts`.
- `GET /api/v1/admin/accounts` only filters by `local`, `remote`, `suspended`, `username`, which matches the start of
  the username, and `by_domain`. Accounts are ordered by their random IDs, not by age, and deleted accounts aren't
  listed.
- An account only has `id`, `username`, `domain`, `email`, `created_at`, `role`, `suspended`, and `account`.
  - `role` only has a `name`, of `Admin` or `User`, or is null for a remote account.
  - `email` is null for a remote account, and for a local one that signed up with an invite or was made from the command
    line.
- Sign-ups waiting for approval aren't accounts yet, so they aren't listed, and the `pending` filter and the `approve`
  and `reject` endpoints don't exist. Admins approve them on the `/admin/sign-ups` page.
- `type` has to be `suspend`, and other values get a 422, rather than doing more than an admin meant. Other parameters
  are ignored.
- `DELETE` doesn't need the account to be suspended first. It's only for local accounts, and gets a 403 for a remote
  one.
- Suspending or deleting yourself, or the server's last admin, gets a 403, with the reason in `error`.

## Deleting your own account

There's no API for people to delete their own accounts, so that an application can't do it with a token. It's only on
the `/settings/delete-account` page. People can also hide their followers and following on the `/settings` page.

## Moving your account

As on Mastodon, there's no API for aliases or moving. They're on the `/settings/aliases` and `/settings/move` pages. A
local account that has moved has `moved` set, as one on another server does, but keeps working: it can still post and
follow, and can stop being shown as moved. The `moved` account never has a `moved` of its own.

## API documentation

API documentation is public, with Swagger UI at `/api-docs/ui` and the OpenAPI document at `/api-docs` or
`/api-docs.yaml`. It describes each endpoint and how it differs from Mastodon, with a link to Mastodon's documentation
for it. It also includes the OAuth endpoints, the scopes each endpoint needs, and the ActivityPub, WebFinger, and
NodeInfo endpoints that other servers use. A scope such as `read` also grants the granular scopes under it.

To sign in from Swagger UI, first register an application with the redirect URI
`<base-url>/api-docs/swagger-ui/oauth2-redirect.html`, and then enter its `client_id` and `client_secret` under
Authorize. Swagger UI builds that redirect URI from the request, so behind a reverse proxy, the server has to
[trust the proxy's forwarded headers](configuration.md#reverse-proxies), or the sign-in is refused.
