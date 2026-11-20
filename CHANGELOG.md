# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- People get an `update` notification when a post they boosted is edited, as on Mastodon. See `docs/api.md`.
- Apps can list a user's favourites, and who favourited or boosted a post, through the Mastodon API's
  `GET /api/v1/favourites`, `GET /api/v1/statuses/:id/favourited_by`, and `GET /api/v1/statuses/:id/reblogged_by`.
- Admins can write the server's rules on the admin page. The sign-up page lists them, and apps show them through the
  Mastodon API's `GET /api/v1/instance/rules` and the instance endpoints. Reports can't cite them yet. See
  `docs/api.md`.
- Apps can read a user's posting and reading defaults through the Mastodon API's `GET /api/v1/preferences`. Only the
  quote policy can be changed so far. See `docs/api.md`.
- People can mute a thread from apps that support it, through the Mastodon API's `POST /api/v1/statuses/:id/mute` and
  `/unmute`, to stop getting notifications about its posts. See `docs/api.md`.

## [0.3.0] - 2026-11-20

### Added

- Bookmarks, as on Mastodon, for apps that support them. They're private, and search finds bookmarked posts. See
  `docs/api.md`.
- Profile pages have a follow button. Visitors from other servers can enter their address to follow from their own server,
  and people here can follow from profiles on Mastodon and other servers that support remote follow. See
  `docs/accounts.md` and `FEDERATION.md`.
- Profile links on remote accounts show as verified when the linked website links back to the account with `rel="me"`.
  Previously stored profiles are checked when they're next fetched.
- Moving accounts, as on Mastodon. Someone moving here adds their old account as an alias at `/settings/aliases` first.
  Someone moving away moves their followers at `/settings/move`, which needs their password and works once every 30
  days. Their account keeps working here, but shows as moved. When an account that people here follow moves, they're
  moved to follow the new account, with its lists, blocks, and mutes carried over, and get a `move` notification. See
  `docs/accounts.md`, `docs/api.md`, and `FEDERATION.md`.
- Scheduled posts, as on Mastodon, for apps that support them. A post can be scheduled more than a minute ahead, and is
  posted within about a minute of its time. Each user can have up to 300 scheduled, and 25 on one day. One that can no
  longer be posted then, such as a reply to a post that's been deleted, is dropped. See `docs/api.md`.
- People who approve followers by hand can choose at `/settings` to approve requests from people they follow straight
  away. Turning it on approves those requests that are already waiting. See `docs/accounts.md`.
- People can choose whether the quotes of their posts that aren't approved straight away, such as those of people who
  don't follow them when only followers' quotes are, are rejected or wait for them to approve by hand. They can choose
  it for all of their new posts or for each one, in apps that support it, and review quotes with GoToSocial's
  interaction requests. Quotes waiting for them give a `pending.quote` notification. A rejected quote can be approved later. Someone whose quote was rejected or revoked can ask again, at
  most once an hour, from here or from their server, and asking again always waits for the quoted author. See `docs/api.md` and `FEDERATION.md`.
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
- A job that has to fetch from a server already busy with this server's other fetches, such as an activity whose author
  isn't stored yet, now waits and tries again, from a few seconds up to a minute for a job that's been waiting longer.
  Before, it failed and was retried a minute or more later, and a large backlog of such jobs could come close to being
  given up on. Searching for an account or a post on such a server now waits up to 2 seconds for it, where before it
  found nothing. Posting waits the same way for each account it mentions there, though a scheduled post doesn't wait.
- An edit from another server of an older post that never arrived here, such as one whose delivery was held up, now
  stores it, in its place by date and without notifying anyone. Older direct messages are still left out. Before, edits
  of posts more than 3 days old were dropped.
- Approving a follow request gives the user a follow notification from the new follower, in place of the request's
  notification. That includes requests approved automatically, which can give several at once, such as when a user stops
  approving followers by hand. Requests approved before upgrading don't get one.
- Each kind of upload is held to its own size limits before it's received. Before, every upload could be as large as the
  largest limit, such as a 100 MB avatar, before it was refused. Uploads now have to say how large they are, and one sent
  in chunks without a size is refused. Browsers and nginx always give the size, but behind a proxy that passes request
  bodies on as they arrive, such as Caddy, some apps' uploads may now fail. See `docs/configuration.md`.
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
  replies its server lists are fetched in the background. Apps that support Mastodon's async refreshes can tell
  when that's done, and others show what was found when the thread is opened again. Only public and unlisted posts are fetched, and a thread is fetched at most every 15 minutes.
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
- Importing and exporting custom emoji packs. Admins can import a Misskey export, a Pleroma or Akkoma pack, or a .zip or
  .tar.gz of images at `/admin/custom-emojis`, or with the `import-emojis` command for large packs, and choose whether
  emoji that already exist are skipped or replaced. Exporting, from the page or with `export-emojis`, makes one .zip that
  this server, Misskey, and Akkoma can all import. The page takes packs of up to 50 MB with up to 1,000 files, which
  `elysium.custom-emojis.max-pack-size` and `elysium.custom-emojis.max-pack-files` change. See `docs/accounts.md`.

### Changed

- Requests to the inboxes that are unsigned, wrongly signed, or too large are now limited by address, so a reverse proxy
  no longer has to limit them. An address that has 60 refused in a minute gets a `429` until the minute is up, and
  `elysium.federation.inbox-limits.refused-per-minute` changes this. Valid deliveries don't count, so another server
  only gets near the limit in a burst of signatures that can't be checked, such as just after it changes its keys. The
  limit is skipped for requests from the server's own network, which usually means a proxy is hiding its clients'
  addresses. A proxy limit on inbox requests is still worth keeping, to stop floods before they reach the server. See
  `docs/configuration.md`.

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
