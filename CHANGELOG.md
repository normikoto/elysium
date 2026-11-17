# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
- An admin front page at `/admin`, which links to every admin page. Admins find a link to it on the front page and their
  settings page.
- Importing and exporting custom emoji packs. Admins can import a Misskey export, a Pleroma or Akkoma pack, or a .zip or
  .tar.gz of images at `/admin/custom-emojis`, or with the `import-emojis` command for large packs, and choose whether
  emoji that already exist are skipped or replaced. Exporting, from the page or with `export-emojis`, makes one .zip that
  this server, Misskey, and Akkoma can all import. The page takes packs of up to 50 MB with up to 1,000 files, which
  `elysium.custom-emojis.max-pack-size` and `elysium.custom-emojis.max-pack-files` change. See `docs/accounts.md`.

### Changed

- Requests to the inboxes that are unsigned, wrongly signed, or too large are now limited by address, so a reverse proxy
  no longer has to limit them. An address that has 30 refused in a minute gets a `429` until the minute is up, and
  `elysium.federation.inbox-limits.refused-per-minute` changes this. Valid deliveries don't count, so this doesn't
  affect other servers that sign their requests correctly. A proxy limit on inbox requests is still worth keeping, to
  stop floods before they reach the server. See `docs/configuration.md`.

### Fixed

- Finding a post at `/admin/statuses` by its link could show a post from another server that claimed that link as its own
  page. A post is now only found by a page on its own server.
- When a server reused a `Follow`'s ID to follow a second account here, an `Undo` of it could leave one of the two follows
  behind, and still send its `Accept`. It now removes both.

## [0.1.0] - 2026-11-15

Initial release.

[Unreleased]: https://github.com/normikoto/elysium/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/normikoto/elysium/releases/tag/v0.1.0
