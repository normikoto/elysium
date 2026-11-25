# Changelog

This file records notable changes to Elysium.

Elysium follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and [Semantic
Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Elysium now serves each public conversation that starts here as a FEP-f228 collection of posts, named by the
  `context` of its posts, so forums such as NodeBB and Discourse, and other servers that read it, can show the whole
  conversation. See [Federation](FEDERATION.md#threads).
- When someone opens a conversation from another server whose posts name a FEP-f228 `context`, as NodeBB, Discourse,
  Lemmy, PieFed, and Mastodon's do, Elysium fills it in from that list of the conversation's posts, rather than only
  from replies. See [Federation](FEDERATION.md#fetching-threads).
- When a reply from another server arrives before the post it answers, Elysium now fetches that post and the ones
  above it in the background, so the conversation shows without anyone opening it first. See
  [Federation](FEDERATION.md#fetching-threads).
- People can choose to be listed in the directory, at `/settings` or from an app, as on Mastodon. Anyone can browse
  this server's listed accounts at `/directory`, linked from the front page, and apps can list them with Mastodon's
  `GET /api/v1/directory`, along with the accounts on other servers that chose the same. Other servers are told about
  the choice, as `discoverable`. It's off for everyone until they choose it, and you can turn the directory off with
  `elysium.profiles.directory`. See [Profiles](docs/accounts.md#profiles) and [Using the API](docs/api.md#directory).
- People can sign up from apps that offer it, such as Mastodon's, through `POST /api/v1/accounts`. The same sign-up
  mode, invites, email confirmation, approval, and limits apply as at `/signup`. The app gets a token for the new
  account, which starts working once the account is ready. See [Sign-ups](docs/accounts.md#sign-ups) and [Using the
  API](docs/api.md#signing-up).
- Apps that still read the direct timeline, `GET /api/v1/timelines/direct`, such as pleroma-fe, can show direct
  messages. Mastodon removed it, but Pleroma still has it. See [Using the API](docs/api.md#timelines).
- People can feature up to 10 hashtags on their profile from an app, as on Mastodon, and apps can list an account's
  featured hashtags and its posts with one of them. Featured hashtags aren't sent to other servers. See [Using the
  API](docs/api.md#tags).
- People can follow hashtags from an app, as on Mastodon. Public posts with a followed hashtag appear in their home
  timeline, including posts from accounts they don't follow, unless the author is blocked or muted. Only posts that
  reach this server appear, because other servers aren't told about tag follows. See [Using the
  API](docs/api.md#tags).
- Notifications include Pleroma's `pleroma.is_seen`, so apps that use it can show which are new, and apps can mark
  them as seen with Pleroma's `POST /api/v1/pleroma/notifications/read`. A notification is seen once the notifications
  marker reaches it, as with unread counts. See [Using the API](docs/api.md#seen-notifications).
- Apps can send push notifications to phones and browsers through `/api/v1/push/subscription`, as on Mastodon,
  including Pleroma's `pleroma:emoji_reaction` for emoji reactions. The server creates its push key the first time
  it's needed, and nothing has to be configured. Elysium only sends to push services on public addresses. See [Using
  the API](docs/api.md#push-notifications).
- People can mark their account as a bot, or say they're a cat as Misskey has it, at `/settings` or in an app. Bots are
  sent to other servers as `Service` actors, and cats with Misskey's `isCat`, which Elysium now reads from other servers
  too. Profile pages show both. Apps get `bot`, and Elysium's own `is_cat`, through `update_credentials` and on
  accounts. See [Profiles](docs/accounts.md#profiles), [Using the API](docs/api.md#profile), and
  [Federation](FEDERATION.md#actors).
- NodeInfo and the instance API now publish active account counts for server lists and apps. NodeInfo includes
  `activeMonth` and `activeHalfyear`, and `GET /api/v2/instance` reports accounts active in the past 30 days in
  `usage.users.active_month`. `GET /api/v1/instance/activity` reports 12 weeks of posts, logins, and sign-ups.

  Elysium records the days each account is active starting with this upgrade, so the initial counts will be low. Set
  `elysium.usage.publish=false` to hide the counts. See [Using the API](docs/api.md#instance) and
  [Federation](FEDERATION.md#nodeinfo).
- People can import and export the accounts they follow, block, and mute as CSV files at `/settings/imports`, in the
  same format as Mastodon, so they can bring them along when moving between servers. An import only adds to what they
  already follow, block, or mute. See [Importing and exporting follows, blocks, and
  mutes](docs/accounts.md#importing-and-exporting-follows-blocks-and-mutes).
- People can enable automatic post deletion at `/settings/post-cleanup`, as on Mastodon. They choose how old posts
  must be and which ones to keep, such as pinned posts, direct messages, or posts with enough favourites. Cleanup also
  undoes old boosts and deletes posts in small batches so other servers receive fewer deletions at once. See [Deleting
  old posts automatically](docs/accounts.md#deleting-old-posts-automatically).
- Set `elysium.instance.account-domain` to give users addresses such as `@alice@social.example` when Elysium runs at
  `https://elysium.social.example`. Set up the WebFinger redirect on the account domain. Existing users’ addresses
  change on restart, and other servers learn the new ones when they refresh the accounts. See [Account
  domain](docs/configuration.md#account-domain).
- Apps can search for accounts through the Mastodon API's `GET /api/v1/accounts/search`, which they use to suggest
  people while someone writes a mention. Both searches can now find only the accounts the user follows, as Mastodon's
  web app does when adding people to a list. See `docs/api.md`.
- Apps can show which of the people someone follows also follow an account, through the Mastodon API's
  `GET /api/v1/accounts/familiar_followers`. An account that hides its followers doesn't have them shown.
- Every account and the instance actor now publish an Ed25519 key in their actor documents (FEP-521a). Outbound
  activities use that key to carry an integrity proof (FEP-8b32), so peers can verify activities they receive through
  a relay. The first startup after upgrading may take a few seconds longer on servers with many accounts because
  Elysium generates keys for existing accounts. See [Integrity proofs](FEDERATION.md#integrity-proofs).
- Elysium now accepts forwarded activities with a valid integrity proof from the author’s server (FEP-8b32). This
  includes replies that Mastodon forwards when the author’s server signs them, as Mitra, Elysium, and Fedify-based
  servers do. These deliveries now work even if the origin is down or refuses a fetch. See [Forwarded
  activities](FEDERATION.md#forwarded-activities).
- People can remove someone from their followers without blocking them, from apps that support it, through the
  Mastodon API's `POST /api/v1/accounts/:id/remove_from_followers`. A follower on another server is told, and can follow
  again later.
- Announcements, as on Mastodon. Admins write them at `/admin/announcements`, and apps show them through the Mastodon
  API's `GET /api/v1/announcements`, where people can dismiss them and react to them with emoji. See `docs/accounts.md`
  and `docs/api.md`.
- People get an `update` notification when a post they boosted is edited, as on Mastodon. See `docs/api.md`.
- When following someone from an app, people can choose to hide that account's boosts, to see only its posts in some
  languages, and to be notified of its new posts, as on Mastodon. Following again changes these choices. Apps could
  send them before, but they were ignored, so existing follows show boosts and every language, and don't notify.
- Apps can list a user's favourites, and who favourited or boosted a post, through the Mastodon API's
  `GET /api/v1/favourites`, `GET /api/v1/statuses/:id/favourited_by`, and `GET /api/v1/statuses/:id/reblogged_by`.
- Admins can write the server's rules on the admin page. The sign-up page lists them, and apps show them through the
  Mastodon API's `GET /api/v1/instance/rules` and the instance endpoints. Reports can't cite them yet. See
  `docs/api.md`.
- Apps can read a user's posting and reading defaults through the Mastodon API's `GET /api/v1/preferences`. Only the
  quote policy can be changed so far. See `docs/api.md`.
- People can mute a thread from apps that support it, through the Mastodon API's `POST /api/v1/statuses/:id/mute` and
  `/unmute`, to stop getting notifications about its posts. See `docs/api.md`.
- Private notes on accounts, as on Mastodon, for apps that support them. Only the person who writes a note sees it, and
  it's never sent to other servers. See `docs/api.md`.
- People can endorse accounts they follow from apps that support it, through the Mastodon API's
  `POST /api/v1/accounts/:id/endorse` and `/unendorse`, or the older `/pin` and `/unpin`, and apps can list them with
  `GET /api/v1/endorsements`. Endorsements aren't sent to other servers. See `docs/api.md`.
- People can give their profile a background image, which is shown behind it on its page here, at `/settings` or from
  apps that support Pleroma's profile backgrounds, such as Pleroma-FE and Akkoma-FE. Backgrounds aren't sent to other
  servers, and those from other servers aren't shown. See `docs/accounts.md` and `docs/api.md`.

### Fixed

- The Mastodon API now accepts trailing slashes, as Mastodon does. Apps such as Brutaldon can connect using paths like
  `/api/v1/instance/` or `/api/v2/instance/`, which previously returned 401.
- The settings page is wider, and the avatar and header previews fit within the page instead of being shown at the
  uploaded image's full size.

## [0.3.0] - 2026-11-20

### Added

- Bookmarks, as on Mastodon, for apps that support them. They're private, and search finds bookmarked posts. See
  `docs/api.md`.
- Profile pages have a follow button. Visitors from other servers can enter their address to follow from their own server,
  and people here can follow from profiles on Mastodon and other servers that support remote follow. See
  `docs/accounts.md` and `FEDERATION.md`.
- Profile links on remote accounts show as verified when the linked website links back to the account with `rel="me"`.
  Previously stored profiles are checked when they're next fetched.
- Moving accounts, as on Mastodon. Someone moving here adds their old account as an alias at `/settings/aliases`
  first. Someone moving away moves their followers at `/settings/move`, which needs their password and works once
  every 30 days. Their account keeps working here, but shows as moved.

  When an account that people here follow moves, they're moved to follow the new account, with its lists, blocks, and
  mutes carried over, and get a `move` notification. See `docs/accounts.md`, `docs/api.md`, and `FEDERATION.md`.
- Scheduled posts, as on Mastodon, for apps that support them. A post can be scheduled more than a minute ahead, and
  is posted within about a minute of its time. Each user can have up to 300 scheduled, and 25 on one day. One that can
  no longer be posted then, such as a reply to a post that's been deleted, is dropped.

  See `docs/api.md`.
- People who approve followers by hand can choose at `/settings` to approve requests from people they follow straight
  away. Turning it on approves those requests that are already waiting. See `docs/accounts.md`.
- People can choose whether the quotes of their posts that aren't approved straight away, such as those of people who
  don't follow them when only followers' quotes are, are rejected or wait for them to approve by hand. They can choose
  it for all of their new posts or for each one, in apps that support it, and review quotes with GoToSocial's
  interaction requests. Quotes waiting for them give a `pending.quote` notification. A rejected quote can be approved
  later.

  Someone whose quote was rejected or revoked can ask again, at most once an hour, from here or from their server, and
  asking again always waits for the quoted author. See `docs/api.md` and `FEDERATION.md`.
- Editing a post can change what it quotes, or remove the quote, in apps that send `quoted_status_id` when editing.
  Mastodon doesn't allow this. A new quote is approved as it would be in a new post. See `docs/api.md` and
  `FEDERATION.md`.

### Changed

- Followers-only pinned posts now show on profiles from other servers, such as Mastodon, to people here who follow the
  account. Pins are read signed as the account here that has followed it the longest. Other servers that ask for a
  user's pins, signed as one of their followers, now get their followers-only pinned posts too, if that follower could
  already fetch them. See `FEDERATION.md`.
- Blocking someone revokes their quotes of your posts for good, as though you had revoked each one, and tells their
  server. Unblocking them doesn't bring the quotes back, though they can then ask again. Blocks made before upgrading
  don't revoke anything.
- A post that another server forwards, such as a reply that Mastodon passes on to the followers of the account being
  replied to, is now kept when the server it came from serves the post but not the activity that created it, as long as
  it was posted in the last 3 days. A forwarded edit of a post, such as Mastodon's, is now applied the same way. Before,
  both were dropped.
- The public pages have a new look, with light and dark themes that follow the visitor's device. Posts show their
  author's name and avatar, and how many replies, boosts, and likes they have. Profiles show how many posts, follows, and
  followers the account has, and have tabs for posts with replies, at `/users/<username>/with_replies`, and posts with
  media, at `/users/<username>/media`.
- Background fetches for a peer already handling other Elysium fetches now wait and retry after a few seconds, up to a
  minute for jobs that have waited longer. Previously those jobs failed and retried a minute or more later, and a
  large backlog could exhaust retries. Searches and mentions in new posts now wait up to two seconds per lookup when
  the peer is busy. Scheduled posts skip that wait.
- An edit from another server of an older post that never arrived here, such as one whose delivery was held up, now
  stores it, in its place by date and without notifying anyone. Older direct messages are still left out. Before, edits
  of posts more than 3 days old were dropped.
- Approving a follow request gives the user a follow notification from the new follower, in place of the request's
  notification. That includes requests approved automatically, which can give several at once, such as when a user stops
  approving followers by hand. Requests approved before upgrading don't get one.
- Elysium checks each upload’s own size limit before receiving its body. Uploads must include a length, and chunked
  uploads without one are rejected. Browsers and nginx provide a length, but apps that send chunked uploads through a
  streaming proxy such as Caddy may now fail. See [Media](docs/configuration.md#media).
- Copies of other servers' avatars and headers are removed after `elysium.attachments.remote-cache-retention`, as
  attachments' copies are. Copies kept before upgrading count from the upgrade.
- An attachment, avatar, or header from another server whose copy was removed, or that hasn't been copied yet, is
  downloaded when someone views it, rather than loaded from its server, so the other server doesn't see your users'
  addresses. Apps get a `/media_proxy/` URL for it, as on Mastodon. Attachments whose copies were removed before
  upgrading come back this way too.
- Visitors who aren't logged in no longer get a login session. Before, each visit to the login or sign-up page, or to an
  app's link for logging in, stored one in the database, with no limit on how many. Login and sign-up forms that are
  open while you upgrade have to be reloaded before they can be sent.
- An activity that's waited 3 days for its turn to be sent to a busy server gives up, as one for a server that keeps
  failing already did, rather than being sent days late.
- The job queue's **History** no longer lists runs where a job only waited, such as for a busy server. A large backlog for
  a server that answers slowly used to add hundreds of thousands of these a day.

## [0.2.1] - 2026-11-17

### Added

- Each release now has the server as a JAR, and a container image on `ghcr.io/normikoto/elysium` for amd64 and arm64
  machines, so installing no longer needs a checkout or a JDK. See `docs/installation.md`.

### Changed

- `compose.yaml` runs the image from the newest release, rather than one built locally. To upgrade with Docker Compose,
  download the release's `compose.yaml` over yours and keep your `.env`, then run `docker compose pull` and
  `docker compose up -d`. To keep running an image you build yourself, see "Building from source" in
  `docs/installation.md`.

## [0.2.0] - 2026-11-17

### Added

- Authorized fetch, as with Mastodon's `AUTHORIZED_FETCH`, for other servers to have to sign their requests to read
  your users' profiles, posts, and follower lists. It's off by default. Turning it on with
  `elysium.federation.authorized-fetch=true` stops blocked servers and suspended accounts on other servers from reading
  them, but web pages stay public. See `docs/configuration.md` and `FEDERATION.md`.
- Threads fill in from other servers. When someone signed in opens a post's thread, the posts it replies to and the
  replies its server lists are fetched in the background. Apps that support Mastodon's async refreshes can tell when
  that's done, and others show what was found when the thread is opened again. Only public and unlisted posts are
  fetched, and a thread is fetched at most every 15 minutes.

  See `docs/api.md` and `FEDERATION.md`.
- Searching for a post's link, from apps, finds the post, as on Mastodon. A post from another server that isn't here yet
  is fetched from its server, when the app asks for that and the post is public or unlisted. This counts toward the same
  limit of 30 lookups a minute as finding accounts on other servers. See `docs/api.md` and `FEDERATION.md`.
- Local-only posts, which are never sent to other servers, and only people on this server can see. Apps that support
  glitch-soc's `local_only` can post them. Replies to them, quotes of them, and boosts of them are local-only too. See
  `docs/api.md` and `FEDERATION.md`.
- Posts written in Markdown, from apps that support glitch-soc's or Pleroma's `content_type`, such as Phanpy. Plain text
  stays the default. Formatting that other servers don't show, such as headings, images, and tables, is simplified or
  dropped, so a post looks the same everywhere. See `docs/api.md` and `FEDERATION.md`.
- An admin front page at `/admin`, which links to every admin page. Admins find a link to it on the front page and their
  settings page.
- Importing and exporting custom emoji packs. Admins can import a Misskey export, a Pleroma or Akkoma pack, or a .zip
  or .tar.gz of images at `/admin/custom-emojis`, or with the `import-emojis` command for large packs, and choose
  whether emoji that already exist are skipped or replaced. Exporting, from the page or with `export-emojis`, makes
  one .zip that this server, Misskey, and Akkoma can all import. The page takes packs of up to 50 MB with up to 1,000
  files, which `elysium.custom-emojis.max-pack-size` and `elysium.custom-emojis.max-pack-files` change.

  See `docs/accounts.md`.

### Changed

- Elysium now limits unsigned, incorrectly signed, and oversized inbox requests by client address. By default, 60
  refusals in a minute trigger `429` until the window resets. Change the limit with
  `elysium.federation.inbox-limits.refused-per-minute`. Valid deliveries are excluded, so a peer generally reaches it
  only after a burst of signature failures, such as after changing keys.

  For public servers, the limit skips requests from the local network because a proxy may be hiding clients’
  addresses. A proxy inbox limit can still stop floods before they reach Elysium. See [Inbox
  limits](docs/configuration.md#inbox-limits).

### Fixed

- Finding a post at `/admin/statuses` by its link could show a post from another server that claimed that link as its own
  page. A post is now only found by a page on its own server.
- When a server reused a `Follow`'s ID to follow a second account here, an `Undo` of it could leave one of the two follows
  behind, and still send its `Accept`. It now removes both.
- A public or unlisted reply to a followers-only or direct post told apps which post it answered and who wrote it, so
  anyone who could see the reply could tell that the hidden post existed. Apps now only see that when they can see the
  post, as web pages already did.

## [0.1.0] - 2026-11-15

Initial release.

[Unreleased]: https://github.com/normikoto/elysium/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/normikoto/elysium/compare/v0.2.1...v0.3.0
[0.2.1]: https://github.com/normikoto/elysium/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/normikoto/elysium/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/normikoto/elysium/releases/tag/v0.1.0
