# Accounts and sign-ups

Sign-ups are off by default. Use `create-user` to create the first account, which becomes [an admin](#admins).
```shell
java -jar elysium.jar create-user alice
```

The command prompts for a password of 8 to 128 characters. When run without a terminal, it reads one line from stdin,
so scripts can pipe in the password. Run `java -jar elysium.jar help` to list the commands.

Run commands with the server’s [configuration](configuration.md). You can run them while the server is up. They apply
pending database migrations, so use the server’s JAR version unless you are upgrading. Put Spring options such as
`--spring.profiles.active=prod` before the command.

Use the same command form for each CLI task, depending on [how you run the server](installation.md).
```shell
# Docker Compose
docker compose run --rm app create-user alice

# Running as a service, as root. This loads the service's settings, without putting secrets on the command line. To pipe
# in a password, use --pipe instead of --pty.
systemd-run --pty --wait --collect --uid=elysium --gid=elysium \
    -p EnvironmentFile=-/etc/elysium/elysium.env \
    -p Environment=SPRING_CONFIG_ADDITIONAL_LOCATION=/etc/elysium/application.properties \
    -p Environment=SPRING_PROFILES_ACTIVE=prod \
    /usr/bin/java -jar /opt/elysium/elysium.jar create-user alice

# Local development. The password is shown as you type it.
./gradlew bootRun --args='create-user alice'
```

Docker Compose may print JVM startup messages before or after the command output.

People can change their password at `/settings/password` by entering their current password. This signs out every
other browser and revokes app access until they authorize the apps again. Admins can run `reset-password alice` to
replace the password, revoke app access, and sign out every browser.

## Sign-ups
Once the first account exists, people can create their own at `/signup`. Who can depends on
[`elysium.registration.mode`](configuration.md):

| Mode               | Who can sign up                                                                     |
|--------------------|-------------------------------------------------------------------------------------|
| `closed` (default) | Only people with an [invite](#invites)                                              |
| `open`             | Anyone, once they've confirmed their email address                                  |
| `approval`         | Anyone who confirms their email address, but they also have to wait for an admin   |

`open` and `approval` need an SMTP server to send the confirmation links, set with
[`spring.mail.host` and `elysium.mail.from`](configuration.md#email). The server won't start in those modes without one.

People who sign up without an invite receive a confirmation link valid for one day. Until they confirm, they cannot
log in and their account is hidden, but the sign-up reserves the username and email address. If the message never
arrives, they can sign up again after the link expires.

After sign-up, Elysium keeps confirmed addresses until the account is deleted. Admins can see them in the [admin
API](api.md). Elysium sends only confirmation and [approval](#approving-sign-ups) emails.

An address can only be used by one account or sign-up at a time. The server only checks that an address looks like one,
then relies on the confirmation link, so it can't stop someone with many addresses from making many accounts. The
`approval` mode can.

Changing to `closed` stops links that haven't been opened yet from working, but sign-ups that are waiting for approval stay
in the list.

### Invites
Invites let someone sign up in any mode, without giving an email address, confirming one, or waiting for approval. So with
`closed`, the server doesn't need an SMTP server at all. Only admins can make them, at `/admin/invites` or with a command:
```shell
java -jar elysium.jar create-invite                                 # Works for any number of accounts, forever
java -jar elysium.jar create-invite --max-uses 1 --expires-in 7d    # One account, within a week
java -jar elysium.jar list-invites
java -jar elysium.jar revoke-invite 5f0c6c1e-...                    # By the ID that list-invites shows
```

Copy the `/signup` link from the page or `create-invite` when you create it. The server only shows it once. Anyone
with the link can use it, so share it only with the intended recipients.

Revoking an invite leaves existing accounts in place. Accounts created by invite have no email address.

### Approving sign-ups
In `approval` mode, the sign-up form asks why the person wants to join. After email confirmation, the request and
their reason appear at `/admin/sign-ups`. Approval creates the account and rejection frees the username and email
address. Elysium emails the person either way.
```shell
java -jar elysium.jar list-sign-ups
java -jar elysium.jar approve-sign-up alice
java -jar elysium.jar reject-sign-up alice
```

Sign-ups waiting for approval never expire, and they aren't accounts yet, so they don't appear at `/admin/accounts` or in
the API.

## Admins
The first account is an admin and later accounts start as regular users. To prevent a visitor from claiming the admin
role, `/signup` requires the first account to exist even if the visitor has an invite.

Admins can open `/admin` from the front page or the bottom of their settings page. It links to all admin pages.

To give or remove the role:
```shell
java -jar elysium.jar set-role alice admin
java -jar elysium.jar set-role alice user
```

Role changes apply immediately, including to active sessions. Removing the role keeps sessions signed in, and
restoring it grants those sessions admin access again. If an admin account may be compromised, remove the role and run
`reset-password`.

Only an admin can authorize an application's admin scopes. Changing an account's role revokes all of its access for its
applications that have admin scopes, so they'll need to be authorized again.

## Suspending and deleting accounts
Admins can suspend or reinstate accounts at `/admin/accounts`, through the [API](api.md), or from the command line.
CLI commands also work when the web server is unavailable.

**Warning:** Deleting a local account is permanent.

```shell
java -jar elysium.jar suspend-account alice
java -jar elysium.jar unsuspend-account alice
java -jar elysium.jar suspend-account https://other.example/users/bob   # An account on another server, by its URI
java -jar elysium.jar delete-account alice                              # Asks you to type the username, unless given --yes
```

The changes take effect immediately. When you run a CLI command, other servers may be notified up to about 10 minutes
later.

**Suspending a local account** keeps its data for as long as it's suspended. While it's suspended:
- It can't log in, and its applications can't use it. If the account may be compromised, also run `reset-password`, because
  its applications' access comes back when the suspension ends.
- Its posts and profile are hidden.
- Other servers [are told](../FEDERATION.md#suspended-and-deleted-accounts), and it keeps its followers. New posts that were
  still being sent are dropped.

**Suspending a remote account** permanently removes its follows. During suspension, Elysium hides its stored posts and
stops exchanging activities with it. Ending the suspension restores the posts. To suspend a whole server, use a
[domain block](#domain-blocks).

**Rejecting media** at `/admin/accounts` removes cached attachments for a remote account and displays links to the
originals. If you allow its media again, Elysium displays and downloads attachments on demand, except those it could
not cache before.

**Deleting a local account** permanently removes its posts and follows. Elysium asks other servers to delete the
account, but they may keep copies. The username stays reserved because other servers may still know it. For remote
accounts, use suspension.

People can permanently delete their account at `/settings/delete-account` by entering their password. At `/settings`,
they can hide their follower and following lists from other people and servers while leaving the counts visible.

People can turn on manual follow approval at `/settings` or in an app and handle requests in an app. Pending requests
give no access to followers-only posts. Turning approval off accepts all pending requests, and turning it on keeps
existing followers. At `/settings`, they can also turn on automatic approval for people they follow, including their
pending requests.

At `/settings` or in an app, people can let anyone find their public posts in search. This is off by default. People
on this server can always find posts they wrote, favourited, bookmarked, or are mentioned in.

Elysium shares the choice with other servers, which decide how to apply it. General search includes remote public
posts only if their authors opted in.

## Moving accounts

**Warning:** Moving followers to another account cannot be undone.

A move transfers followers to another account on the same server or on a different server.

- **Moving here:** they add their old account as an alias at `/settings/aliases`, then move it from its own server.
- **Moving away:** they add this account as an alias on the new one, then enter its address and their password at
  `/settings/move`. An account can only move once every 30 days.

When an account moves, wherever it is:

- People here who follow it follow the new account instead, and are notified. A follower blocked by the new account keeps following
  the old one. Following an account that approves followers by hand waits for its approval.
- The new account is put in the lists that had the old one.
- People here who block or mute the old account block or mute the new one too, unless they follow it.
- A moved account here keeps its posts, and the accounts it follows aren't moved. It keeps working, but shows as moved,
  and other servers may treat it as closed. Its owner can clear the moved label at `/settings/move`. Transferred
  followers stay on the new account.

Other servers only move their people's follows if the new account lists the old one as an alias first. Only one move from
an account on another server is acted on each week.

## Profiles
People can change their profile at `/settings`, or from an app: a display name of up to 30 characters, a bio of up to 500,
an avatar, a header, a background, and up to 4 fields, each a name and a value. You can change how many fields there can
be with [`elysium.profiles.max-fields`](configuration.md). Lowering it doesn't remove anyone's fields, but they can't save
their fields again until they've removed enough.

Profile images can be PNG, JPEG, GIF, or WebP, up to [`elysium.profiles.max-image-size`](configuration.md). Elysium
converts them to WebP, strips metadata such as location, and preserves animation. It crops avatars and headers and
scales down backgrounds as needed.

Backgrounds appear on local profile pages and in apps that support Pleroma backgrounds. Elysium keeps them local. It
deletes replaced images and images from deleted accounts. Images from suspended accounts remain accessible by their
URLs even though the profile is hidden.

A field whose value is only a link starting with `https://` is marked as verified if the page there links back to the
profile with `rel="me"`. The server fetches each of these pages when the profile is saved, which means anyone who can
save a profile can make your server fetch a page. It only fetches public addresses.

Other servers that follow the account are told about each change.

At `/settings` or in an app, people can mark their account as a bot, for one that posts automatically. Their profile
page, apps, and other servers show it as a bot. They can also say they're a cat, which their profile page shows, and
which Misskey and the servers based on it show as cat ears. Both are off by default, and other servers are told when
either changes.

Signed-in users can follow an account from its profile page. A visitor from another server can enter their address,
such as `@alice@mastodon.social`, to follow from their own server if it supports remote follow, as Mastodon, Akkoma,
and Misskey do. Otherwise, Elysium directs them to search for the profile in their app. Local users who enter their
address on another server’s remote-follow form return to Elysium to follow the account.

When someone looks up an account on another server, this server asks that server about it. Each person can make 30 of these
lookups a minute, so that nobody can use this server to flood another one with requests.

Nobody can suspend or delete the server's last admin, so [choose another](#admins) first. Admins also can't suspend or delete
themselves from the admin page or the API.

## Blocks and mutes
People can block, mute, endorse, or write notes about other accounts, and block whole servers, from an app. These are their own
choices, separate from an admin's [domain blocks](#domain-blocks), and other people can't see them.

- **Blocking** someone ends the follows between them both ways, removes the notifications between them, and stops either
  from following the other or seeing the other's posts here. Someone on another server is told about the block, so that
  their server can do the same, unless you turn that off with
  [`elysium.federation.send-blocks`](configuration.md). With it off, their server is only told that the follows ended,
  and can keep showing them public posts. Either way, anyone can still read public posts without signing in.
- **Muting** someone hides their posts and, unless the person chooses otherwise, their notifications, but leaves follows as
  they are, and the muted person isn't told. A mute can be set to end after a while.
- **Notes** are private notes people write about an account, which only they see. Nobody else is told, including the
  account's server.
- **Endorsing** an account someone follows lists it in their apps. Only they see it, and it ends when they stop following
  the account. Nobody else is told, including the account's server.
- **Blocking a server** hides its accounts and removes followers there, as blocking each of them would, but isn't sent to
  that server. Subdomains are blocked too.

What someone on another server posts can still reach this server through the people here who follow them, and blocks don't
stop it being stored, only shown.

### Importing and exporting follows, blocks, and mutes
People can download the accounts they follow, block, or mute as CSV files at `/settings/imports`, and import those files
there, as on Mastodon. Files exported here and from Mastodon both work, in either direction.

- An import only adds. Accounts already followed, blocked, or muted that aren't in the file stay as they are, and a mute
  that's already there keeps when it ends.
- A file can list up to 20,000 accounts. Accounts on other servers that this server hasn't seen are looked up about 30 a
  minute, so a large import can take hours. This limit is separate from the one on searches, so an import doesn't stop
  the person from looking up accounts in an app.
- Accounts that approve their followers get a follow request, as when following them from an app.
- The page shows how many accounts are left, and how many couldn't be added once the import is done, such as accounts
  that no longer exist or are on a blocked server.
- Each import sends the same follows and blocks to other servers as following or blocking each account would.
- An export of follows leaves out follow requests that haven't been approved yet.
- Exports of blocks and mutes include accounts that have been suspended, so that they stay blocked or muted after a move.
- If the person's account is suspended during an import, the import waits, and carries on if the suspension is lifted.

## Deleting old posts automatically

**Warning:** Automatic post deletion permanently removes old posts.

People can enable it at `/settings/post-cleanup` and choose an age from one week to two years. As on Mastodon, they
can keep pinned posts, direct messages, posts with polls or media, posts they favourited or bookmarked themselves, and
posts with enough favourites or boosts. The server undoes older boosts regardless of those choices.

Elysium deletes attachments with each post and asks other servers to delete their copies, just as it does when the
author deletes a post. It removes posts in small batches, so a large backlog can take days. A post that stops
qualifying for an exception, such as one the author unpins, may also take time to be deleted. Cleanup pauses for
suspended accounts.

## Removing posts

**Warning:** Removing a post is permanent.

At `/admin/statuses`, find the post by its URL or ID.

- **A post from this server** is deleted as if its author had deleted it, attachments included, and other servers are
  asked to delete it too, which they may not do.
- **A post from another server** is only removed here, and its server isn't asked to delete it, though boosts of it by
  your users are undone. It isn't accepted again, even if its server sends it again.

The page only shows the content of public and unlisted posts, so admins don't get to read followers-only and direct posts
by looking them up.

## Announcements
Admins write announcements at `/admin/announcements`. Apps that support them, such as Mastodon's web interface, show
published ones to everyone with an account here, who can dismiss them and react to them with emoji. They're never sent to
other servers.

- An announcement is plain text, and links and hashtags in it work. Custom emoji show when written as `:blobcat:`.
- It can be kept as a draft, which nobody else sees, and published later. Unpublishing it hides it again, and keeps its
  reactions for when it's published again.
- An announcement about an event, such as maintenance, can have a start and an end, entered in UTC, which apps show with
  it. It's still listed before and after them, until you unpublish or remove it.
- People can react with Unicode emoji and this server's custom emoji, up to 8 different ones on each announcement.
- Removing an announcement can't be undone, and removes its reactions.

## Custom emoji
Admins manage custom emoji at `/admin/custom-emojis`. People can type a shortcode such as `:blobcat:` in posts,
display names, bios, and profile fields. Images are stored as WebP and keep their animation. The 256 KB default limit
applies to local and remote emoji and can be changed with [`elysium.custom-emojis.max-image-size`](configuration.md).

Apps list custom emoji in their pickers. People can still type the shortcodes of emoji hidden from the picker.

An emoji can have a category, and apps show each category as its own section of their pickers, with the uncategorized
emoji together, usually as "Custom". The admin page lists the emoji by category, with the uncategorized ones last. You can
rename a category, which merges it with another if you give it that one's name, or make all its emoji uncategorized, and
move several emoji at once by selecting them. A category can't be called "Uncategorized", which is what the page calls
the emoji with none.

- Posts only show the emoji that existed when they were written or last edited, so adding an emoji doesn't change older
  posts that happened to use its shortcode.
- Removing an emoji shows its shortcode as text everywhere it was used. Other servers keep the copies they were sent.
- Emoji from other servers are downloaded and kept here, as their attachments are, and are removed when no post here uses
  them any more. A domain block that rejects a server's media stops its emoji being downloaded too.

### Importing and exporting packs
The admin page can import a pack of emoji at once. A pack can be:

- **A Misskey export**: a .zip with a `meta.json`. The emoji keep their categories, and those with none stay uncategorized.
- **A Pleroma or Akkoma pack**: a .zip with a `pack.json`, as Akkoma's pack archives are, or a pack's folder zipped up.
  The emoji are put in a category named after the pack's folder, or after the .zip if it has no folder.
- **A .zip, .tar.gz, or .tar of images**, as Mastodon's `tootctl emoji import` takes. Each emoji is named after its file,
  so `blobcat.png` becomes `:blobcat:`. The emoji are put in a category as Akkoma's are.

You can assign one category to the whole pack. The upload rules apply to every image. Elysium lists invalid or
oversized files and imports the rest.

Importing skips existing shortcodes unless you choose to replace them. A replacement uses the pack’s image and
category in existing posts and reactions. Other servers keep the original image.

The page accepts packs up to 50 MB with up to 1,000 files. Change these limits with
[`elysium.custom-emojis.max-pack-size` and `max-pack-files`](configuration.md). Use `import-emojis` for larger packs
or imports that outlast the proxy timeout. The command has neither limit.

```shell
java -jar elysium.jar import-emojis blobs.zip                       # Skip emoji that already exist
java -jar elysium.jar import-emojis blobs.zip --replace --category Blobs

# Docker Compose: the pack has to be mounted into the container
docker compose run --rm -v "$PWD/blobs.zip:/tmp/blobs.zip:ro" app import-emojis /tmp/blobs.zip
```

**Exporting** downloads every emoji as one .zip, from the link on the admin page or with
`java -jar elysium.jar export-emojis emojis.zip`. Emoji hidden from pickers are included, but the export doesn't record
that they're hidden, and they're shown in pickers after an import. The export can be imported here, and:

- **On Misskey**, at *Control panel → Custom emoji → Import*, once the .zip is uploaded to the admin's drive. Categories
  are kept.
- **On Akkoma or Pleroma**, by unzipping it into a folder of its own in the server's `emoji` directory, such as
  `instance/static/emoji/myserver/`, and then reloading emoji, or restarting. Pleroma 2.10 and later can also import the .zip
  through its admin API. Akkoma doesn't support categories, so every emoji goes in the pack's category. Don't upload the
  .zip as files to an existing Akkoma pack, which would make an emoji of each file in it, the manifests included.

Mastodon can't import it, since `tootctl emoji import` only takes PNG and GIF, and emoji are kept here as WebP.

## Reports
People can report an account, and some of its posts, from their app. Other servers can also send reports about accounts
here. Each admin gets a notification of a new report in their app, and reports are listed at `/admin/reports`, or
through the [API](api.md).

- A report shows every post it's about, even followers-only and direct ones, because whoever reported them chose to share
  them with you. A post that's deleted after it's reported goes from the report.
- A report from another server only says which server sent it, not who on that server reported it.
- When someone here reports an account on another server, they can choose to send the report there too. It's sent as this
  server, so it doesn't say who reported it.
- Resolving a report only marks it as handled. To act on it, suspend the account at `/admin/accounts`, or remove its posts
  from the report's page.
- Reports by or about an account go when it's deleted, or when its server is suspended with a domain block. A server that
  you only limit can still send reports.

## Domain blocks
Admins can suspend or limit a server at `/admin/domain-blocks` or through the [API](api.md). A block takes effect
immediately and also covers subdomains.

- **Suspending** a server permanently removes its follows and posts and stops deliveries in both directions. If you
  lift the suspension or change it to a limit, users on that server have to follow again. The server can still fetch
  public ActivityPub documents unless you enable [authorized fetch](configuration.md#authorized-fetch). See the other
  effects in the [federation reference](../FEDERATION.md#domain-blocks).
- **Limiting** a server lets it federate as usual, apart from what you choose to reject. Rejecting its media shows its
  posts' attachments as links to its server instead of downloading them, and removes the copies already kept, as rejecting
  an account's media does.

Comments on a block are only shown to admins. The list of blocks isn't published, but it isn't secret either, because anyone
can test whether a domain is on it.

## Job queue
Admins can inspect the job queue at `/admin/db-scheduler`. It shows when work such as federation deliveries or account
cleanup will run. **History** shows failures and skips runs that only waited, such as for a busy server. Job records
contain no post text.

- **Retries:** A job that fails for a temporary reason, like a server being down, is tried again with longer and longer gaps,
  for up to 3 days.
- **Giving up:** After that, or straight away if trying again can't help, a job stays in the list as failed for 30 days, so
  that you can retry or delete it. A retried job that fails again stays for another 30 days. Activities are kept for 34
  days, so retrying an older job can fail with "Its activity is gone".
- **Servers that keep failing:** After 3 failures in a row, sending to that server waits, for up to 6 hours. These jobs show
  as scheduled, not failed.
- **Limits:** Each copy of the server sends at most 3 activities at a time to another server, and starts about 3 jobs a
  second for one server, so a large backlog for one server drains at about 180 a minute for each copy. A job that's waited 3 days
  for its turn gives up, as for a server that keeps failing.

## Two-factor login
People can turn on two-factor login at `/settings/two-factor`. After they enter their password, they supply a
six-digit code from an authenticator app such as Aegis, 2FAS, or Google Authenticator. Apps that sign in through the
browser use the same login flow.

- **Turning it on** needs the password and a code from the app. It logs the account out of every other browser. Apps that
  were already authorized keep working.
- **Recovery codes:** Turning it on shows 10 single-use codes that work in place of a code from the app. People can get new
  ones, which replaces the old ones, with their password.
- **Lost phone and recovery codes:** Run `disable-two-factor alice`, after checking that the request really comes from that
  person. Their password is then enough to log in, and they can turn two-factor login on again. `reset-password` doesn't
  turn it off.
- **Turning it off** needs the password.

Keep the server clock synchronized with NTP. Codes can fail if the clock is off by about 30 seconds.

**Warning:** Anyone with a copy of the database can generate two-factor codes.

Protect the database and its backups as you would passwords.

## Authorized apps
People can review an app’s name, website, permissions, and authorization date at `/oauth/authorized_applications`.
Apps supply their own names and websites, which are unverified.

Revoking an app logs it out of that person's account straight away, and it has to ask for approval again. Other people's use
of the same app isn't affected.

Anyone can register an app, without logging in. An app that nobody has authorized a day after it was registered is
removed, and has to register again. Once someone authorizes an app, it's kept, even if they revoke it later.

## Rate limits
Logins, sign-ups, and app registrations are limited, and requests over a limit get a `429` response:

| Attempts                                         | Limit             |
|--------------------------------------------------|-------------------|
| Failed logins from one address                   | 20 per 15 minutes |
| Failed logins for one username from one address  | 5 per 15 minutes  |
| Failed logins for one username from any address  | 50 per hour       |
| Wrong two-factor codes for one account from one address | 5 per 15 minutes |
| Wrong two-factor codes for one account from any address | 50 per hour |
| Sign-ups from one address                        | 5 per hour        |
| Apps registered from one address                 | 30 per hour       |
| Follows from a profile page by visitors from other servers, from one address | 10 per minute |

Wrong two-factor codes also count toward the limit for their address. After 5 wrong codes in a row, logging in starts again
from the password, and so does waiting more than 5 minutes after typing it.

IPv6 addresses are counted by their /64 network. Someone trying many passwords can stop a username from logging in until the
hour is up, including its owner. Counts are kept in memory, so restarting resets them, and each copy of the server counts on
its own.

If the server is behind a reverse proxy, it has to be [set up correctly](configuration.md#reverse-proxies). Otherwise all
clients can share one limit, or clients can get around the limits by making up their addresses.

To turn the limits off, because you enforce your own elsewhere, set `elysium.rate-limit.enabled=false`. This doesn't turn off
the [limit on inboxes](../FEDERATION.md#inboxes), which can't be replaced by one elsewhere, or the limit on follows from
profile pages, since each one makes this server send a request to a server the visitor chooses.

These limits only slow down abuse from a small number of addresses. To stop people with many addresses, use
[`approval` or `closed`](#sign-ups).

## Login sessions
Login sessions survive restarts and work across instances that share the database. They expire after 30 minutes of
inactivity by default. Change that with `server.servlet.session.timeout`. Admins can end all of a person’s sessions
with `reset-password`, and users can end their other sessions by changing their password.

**Warning:** PostgreSQL logs can contain session IDs if you log statement parameters.

Keep those logs private. The database and its backups contain only hashes of session IDs.

Viewing the login or sign-up form creates no session. A session begins at login or after someone enters their password
and is prompted for a two-factor code. Before that, the browser keeps the form state in cookies.
