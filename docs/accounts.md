# Accounts and sign-ups

Sign-ups are disabled by default, and the first account can only be created with the `create-user` command, which makes it
[an admin](#admins):
```shell
java -jar elysium.jar create-user alice
```

It asks for the password, which must be 8 to 128 characters. Without a terminal, it reads the password as one line from
stdin, so scripts can pipe it in. Run `java -jar elysium.jar help` to list the commands.

Commands need the same [configuration](configuration.md) as the server, and are safe to run while it's up. They apply any
pending database migrations, so use the same version of the JAR as the server unless you're upgrading. To pass Spring
options, such as `--spring.profiles.active=prod`, put them before the command.

How you run a command depends on [how you run the server](installation.md). Every command works the same way as
`create-user`:
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

With Docker Compose, some JVM startup messages are printed around the command's output. They don't mean anything went wrong.

People can't change their own passwords yet. To replace someone's password, run `reset-password alice`. This also logs them
out everywhere and revokes their applications' access, so they'll need to authorize them again.

## Sign-ups
Once the first account exists, people can create their own at `/signup`. Who can depends on
[`elysium.registration.mode`](configuration.md):

| Mode               | Who can sign up                                                                     |
|--------------------|-------------------------------------------------------------------------------------|
| `closed` (default) | Only people with an [invite](#invites)                                              |
| `open`             | Anyone, once they've confirmed their email address                                  |
| `approval`         | Anyone who confirms their email address, but they also have to wait for an admin   |

`open` and `approval` need an SMTP server to send the confirmation links, set with
[`spring.mail.host` and `elysium.mail.from`](configuration.md). The server won't start in those modes without one.

Someone signing up without an invite gives an email address, and gets a link to confirm it. The link works for a day. Until
they've confirmed it, they can't log in, and nobody else can see the account or take its username or address. If the email
doesn't arrive, they can sign up again once the day is up. The server only keeps addresses for accounts that confirmed one.
Admins can see them through the [admin API](api.md), and they go when the account is deleted. No other email is sent apart
from the [approval](#approving-sign-ups) messages.

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

The page and `create-invite` show a link to `/signup` to send to whoever you're inviting. Only a hash of it is stored, so
it can't be shown again. Anyone with the link can use it, so give it only to the people it's for. Revoking an invite
doesn't affect the accounts it has already made. Accounts made with an invite have no email address, so they won't get any
email.

### Approving sign-ups
With `approval`, the sign-up page also asks why they want to join. After they've confirmed their address, they're listed at
`/admin/sign-ups`, along with their reason, until an admin approves or rejects them. They're emailed either way. Approving
one creates the account. Rejecting one frees its username and address. The commands do the same:
```shell
java -jar elysium.jar list-sign-ups
java -jar elysium.jar approve-sign-up alice
java -jar elysium.jar reject-sign-up alice
```

Sign-ups waiting for approval never expire, and they aren't accounts yet, so they don't appear at `/admin/accounts` or in
the API.

## Admins
The server's first account becomes an admin, and later accounts are regular users. `/signup` refuses to create the first
account until `create-user` has been run, even with an invite, so that a stranger can't become the admin.

To give or remove the role:
```shell
java -jar elysium.jar set-role alice admin
java -jar elysium.jar set-role alice user
```

Changes apply straight away, including to anyone who's logged in. Removing the role doesn't log them out, and giving it back
restores admin access to any session that's still logged in, so if an admin's account may be compromised, remove the role
and run `reset-password`.

Only an admin can authorize an application's admin scopes. Changing an account's role revokes all of its access for its
applications that have admin scopes, so they'll need to be authorized again.

## Suspending and deleting accounts
An admin can suspend an account, which can be undone, at `/admin/accounts`, through the [API](api.md), or with a command,
which works even when the web server can't be reached:
```shell
java -jar elysium.jar suspend-account alice
java -jar elysium.jar unsuspend-account alice
java -jar elysium.jar suspend-account https://other.example/users/bob   # An account on another server, by its URI
java -jar elysium.jar delete-account alice                              # Asks you to type the username, unless given --yes
```

These apply straight away, but when a command does it, other servers may only be told about 10 minutes later.

**Suspending a local account** keeps its data for as long as it's suspended. While it's suspended:
- It can't log in, and its applications can't use it. If the account may be compromised, also run `reset-password`, because
  its applications' access comes back when the suspension ends.
- Its posts and profile are hidden.
- Other servers [are told](../FEDERATION.md#suspended-and-deleted-accounts), and it keeps its followers. New posts that were
  still being sent are dropped.

**Suspending an account on another server** refuses everything from it, stops sending to it, and removes its follows, which
don't come back if the suspension ends. Its posts stored here are hidden, and come back if the suspension ends. To cut off a whole server, use a [domain block](#domain-blocks).

**Rejecting an account's media**, at `/admin/accounts`, is for an account on another server whose posts are fine but whose
attachments you don't want kept here. Its attachments are shown as links to its server instead of being downloaded, and the
copies already kept are removed. Allowing its media again shows them as what they are, but still as links, since they aren't
downloaded again.

**Deleting a local account** can't be undone. Its posts and follows are removed, and other servers are asked to remove it
too, which they may not do. Its username can't be used again, because other servers can still know it. Accounts on other
servers can't be deleted, so suspend them instead.

People can delete their own account at `/settings/delete-account`, with their password. At `/settings`, they can also hide
who follows them and who they follow, from other people and other servers, which still see how many there are. They can
also choose to approve followers by hand, there or from an app, and then approve or refuse each request in an app. A
request that's waiting doesn't make someone a follower, so they don't see followers-only posts. Turning it off approves
every request that's waiting, and turning it on keeps the followers they already have.

## Profiles
People can change their profile at `/settings`, or from an app: a display name of up to 30 characters, a bio of up to 500,
an avatar, a header, and up to 4 fields, each a name and a value. You can change how many fields there can be with
[`elysium.profiles.max-fields`](configuration.md). Lowering it doesn't remove anyone's fields, but they can't save their
fields again until they've removed enough.

Images can be PNG, JPEG, GIF, or WebP, up to [`elysium.profiles.max-image-size`](configuration.md). They're always
converted to WebP and cropped to size, which removes the information that photos carry, such as where they were taken.
Animations stay animated. Replaced images are deleted, and so is everything in a profile when its account is deleted. A
suspended account's profile is hidden, but its images can still be loaded by anyone who already has their address.

A field whose value is only a link starting with `https://` is marked as verified if the page there links back to the
profile with `rel="me"`. The server fetches each of these pages when the profile is saved, which means anyone who can
save a profile can make your server fetch a page. It only fetches public addresses.

Other servers that follow the account are told about each change.

When someone looks up an account on another server, this server asks that server about it. Each person can make 30 of these
lookups a minute, so that nobody can use this server to flood another one with requests.

Nobody can suspend or delete the server's last admin, so [choose another](#admins) first. Admins also can't suspend or delete
themselves from the admin page or the API.

## Blocks and mutes
People can block or mute other accounts, and block whole servers, from an app. These are their own choices, separate from
an admin's [domain blocks](#domain-blocks), and other people can't see them.

- **Blocking** someone ends the follows between them both ways, removes the notifications between them, and stops either
  from following the other or seeing the other's posts here. Someone on another server is told about the block, so that
  their server can do the same, unless you turn that off with
  [`elysium.federation.send-blocks`](configuration.md). With it off, their server is only told that the follows ended,
  and can keep showing them public posts. Either way, anyone can still read public posts without signing in.
- **Muting** someone hides their posts and, unless the person chooses otherwise, their notifications, but leaves follows as
  they are, and the muted person isn't told. A mute can be set to end after a while.
- **Blocking a server** hides its accounts and removes followers there, as blocking each of them would, but isn't sent to
  that server. Subdomains are blocked too.

What someone on another server posts can still reach this server through the people here who follow them, and blocks don't
stop it being stored, only shown.

## Removing posts
An admin can remove a post that breaks the rules at `/admin/statuses`, by finding it with its URL or its ID. Removing a post
can't be undone.

- **A post from this server** is deleted as if its author had deleted it, attachments included, and other servers are
  asked to delete it too, which they may not do.
- **A post from another server** is only removed here, and its server isn't asked to delete it, though boosts of it by
  your users are undone. It isn't accepted again, even if its server sends it again.

The page only shows the content of public and unlisted posts, so admins don't get to read followers-only and direct posts
by looking them up.

## Domain blocks
Admins can cut off or limit another server at `/admin/domain-blocks`, or through the [API](api.md). A block covers the
domain's subdomains too, and applies straight away. There are two kinds:

- **Suspending** a server means nothing more is accepted from it or sent to it, and its follows and posts are removed.
  Removing the block, or changing it to only limit the server, doesn't bring those back, so followers on that server have
  to follow again. See [`FEDERATION.md`](../FEDERATION.md#domain-blocks) for what a suspension doesn't stop.
- **Limiting** a server lets it federate as usual, apart from what you choose to reject. Rejecting its media shows its
  posts' attachments as links to its server instead of downloading them, and removes the copies already kept, as rejecting
  an account's media does.

Comments on a block are only shown to admins. The list of blocks isn't published, but it isn't secret either, because anyone
can test whether a domain is on it.

## Job queue
Admins can see the background job queue at `/admin/db-scheduler`. It holds the work of sending and receiving activities,
and of cleaning up after blocks, suspensions, and deletions. Each job shows when it'll next run, and **History** shows why it
failed. Jobs don't contain posts, so private posts aren't shown.

- **Retries:** A job that fails for a temporary reason, like a server being down, is tried again with longer and longer gaps,
  for up to 3 days.
- **Giving up:** After that, or straight away if trying again can't help, a job stays in the list as failed for 30 days, so
  that you can retry or delete it. A retried job that fails again stays for another 30 days. Activities are kept for 34
  days, so retrying an older job can fail with "Its activity is gone".
- **Servers that keep failing:** After 3 failures in a row, sending to that server waits, for up to 6 hours. These jobs show
  as scheduled, not failed.
- **Limits:** Each copy of the server sends at most 3 activities at a time to another server, and starts about 3 jobs a
  second for one server, so a large backlog for one server drains at about 180 a minute for each copy.

## Two-factor login
Anyone can turn on two-factor login at `/settings/two-factor`, linked from their settings. Logging in then asks for a
six-digit code from an authenticator app, such as Aegis, 2FAS, or Google Authenticator, after the password. Apps that log in
through the browser get the same page, so they need nothing extra.

- **Turning it on** needs the password and a code from the app. It logs the account out of every other browser. Apps that
  were already authorized keep working.
- **Recovery codes:** Turning it on shows 10 single-use codes that work in place of a code from the app. People can get new
  ones, which replaces the old ones, with their password.
- **Lost phone and recovery codes:** Run `disable-two-factor alice`, after checking that the request really comes from that
  person. Their password is then enough to log in, and they can turn two-factor login on again. `reset-password` doesn't
  turn it off.
- **Turning it off** needs the password.

The server's clock has to be right, within about 30 seconds, or codes won't match. Use NTP, as most systems do already.

Two-factor secrets are stored in the database as they are, because the server needs them to check codes. Anyone with a copy
of the database or a backup could work out codes, so keep them as private as you keep passwords.

## Rate limits
Logins and sign-ups are limited, and requests over a limit get a `429` response:

| Attempts                                         | Limit             |
|--------------------------------------------------|-------------------|
| Failed logins from one address                   | 20 per 15 minutes |
| Failed logins for one username from one address  | 5 per 15 minutes  |
| Failed logins for one username from any address  | 50 per hour       |
| Wrong two-factor codes for one account from one address | 5 per 15 minutes |
| Wrong two-factor codes for one account from any address | 50 per hour |
| Sign-ups from one address                        | 5 per hour        |

Wrong two-factor codes also count toward the limit for their address. After 5 wrong codes in a row, logging in starts again
from the password, and so does waiting more than 5 minutes after typing it.

IPv6 addresses are counted by their /64 network. Someone trying many passwords can stop a username from logging in until the
hour is up, including its owner. Counts are kept in memory, so restarting resets them, and each copy of the server counts on
its own.

If the server is behind a reverse proxy, it has to be [set up correctly](configuration.md#reverse-proxies). Otherwise all
clients can share one limit, or clients can get around the limits by making up their addresses.

To turn the limits off, because you enforce your own elsewhere, set `elysium.rate-limit.enabled=false`. This doesn't turn off
the [limit on inboxes](../FEDERATION.md#inboxes), which can't be replaced by one elsewhere.

These limits only slow down abuse from a small number of addresses. To stop people with many addresses, use
[`approval` or `closed`](#sign-ups).

## Login sessions
Login sessions are stored in the database, so restarting doesn't log anyone out, and with more than one copy of the server,
any of them can serve any browser. A session ends after 30 minutes without a request, which you can change with
`server.servlet.session.timeout`. To end someone's sessions, use `reset-password`.

Only a hash of each session's ID is stored, so the database or a backup can't be used to take over a session. If you log
PostgreSQL statements with their parameters, those logs do contain session IDs, so keep them private. Visitors who
aren't logged in also get sessions, so if you're watching your database's size, look at the `spring_session` table.
