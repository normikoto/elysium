# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

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

### Changed

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

[Unreleased]: https://github.com/normikoto/elysium/compare/v0.2.1...HEAD
[0.2.1]: https://github.com/normikoto/elysium/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/normikoto/elysium/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/normikoto/elysium/releases/tag/v0.1.0
