# Accounts and sign-ups

Sign-ups are currently disabled by default, and the first account can only be created with the `create-user` command,
which makes it [an admin](#admins):
```shell
java -jar elysium.jar create-user alice
```

It asks for the password, which must be 8 to 128 characters. Without a terminal, it reads the password as one line from
stdin, so scripts can pipe it in. Run `java -jar elysium.jar help` to list the commands.

Commands need the same [configuration](configuration.md) as the server, and can run while it's up. Like the server,
they apply any pending database migrations, so run them from the JAR version you're already running unless you're
upgrading. `help` and mistakes in a command line don't need any configuration, and don't touch the database. To pass
Spring options, such as `--spring.profiles.active=prod`, put them before the command.

How you run a command depends on [how you run the server](installation.md). `reset-password` works the same way as
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

With Docker Compose, the image also prints some JVM startup messages and a memory report around the command's output.
They don't mean anything went wrong.

People can't change their own passwords yet, and sign-ups have no email confirmation. To replace someone's password, run
`reset-password alice`. It also logs them out of every browser straight away, with no restart, and revokes the access of all
their applications, so they'll need to authorize them again.

## Admins
The server's first account becomes an admin, and later accounts are regular users. If sign-ups are turned on, `/signup`
refuses to create the first account, and says it's unavailable until `create-user` has been run. Otherwise the first
stranger to sign up would become an admin.

If your server had accounts before this role was added, they stay regular users, so choose an admin with `set-role`. It
also removes the role, and runs the same way as `create-user`:
```shell
java -jar elysium.jar set-role alice admin
java -jar elysium.jar set-role alice user
```

Changes apply straight away, with no restart, including to anyone who's already logged in. If an admin's account may be
compromised, remove the role and run [`reset-password`](#accounts-and-sign-ups). Removing the role doesn't end their browser
sessions by itself. Those stay logged in without admin access, and giving the role back would restore it to them. You can
remove the last admin, and use the same command to choose another.

Applications only get admin scopes from an admin. A regular user can't approve them, and they have no effect on app
tokens, which don't belong to any user, even if the application registered with them. Changing an account's role also
revokes its applications that have admin scopes, along with the rest of their access to that account, so they'll need to
be authorized again.

## Suspending and deleting accounts
To stop an account in a way that can be undone, an admin can suspend it at `/admin/accounts`, through the [API](api.md), or with
a command, which works when the web server can't be reached. Commands run [the same way as `create-user`](#accounts-and-sign-ups):
```shell
java -jar elysium.jar suspend-account alice
java -jar elysium.jar unsuspend-account alice
java -jar elysium.jar suspend-account https://other.example/users/bob   # An account on another server, by its URI
java -jar elysium.jar delete-account alice                              # Asks you to type the username, unless given --yes
```

These apply straight away, with no restart, but a command can leave telling other servers to the running server, which picks
that up within about 10 minutes.

**Suspending a local account** keeps its data, for as long as it stays suspended, with no time limit. While it's suspended:
- It can't log in, its browser sessions end, and its applications' tokens are refused. The tokens work again if the suspension
  ends, so if the account may be compromised, also run [`reset-password`](#accounts-and-sign-ups), which revokes them.
- Its posts and profile aren't shown to anyone, and it isn't counted in the server's statistics.
- Other servers [are told](../FEDERATION.md#suspended-and-deleted-accounts), and it keeps its followers. New posts still waiting
  to be sent are dropped, and aren't sent later. Other things waiting to be sent, such as the deletion of a post, go out once the
  suspension ends.
- `reset-password` and `set-role` still work on it.

**Suspending an account on another server** refuses everything from it and stops sending to it, and removes its follows, which
don't come back if the suspension ends. Its own server isn't told. To stop a whole server, use a [domain block](#domain-blocks).

**Deleting a local account** removes it for good, and can't be undone. Its password and applications' access go straight away,
and its posts and follows are removed by a background job within moments, or about 10 minutes if a command did it. Other servers
are asked to remove it too, which they may not do. Its username can't be used again, because other servers can still know it, so
a row with its username and addresses is kept for good. Replies to its posts from other accounts stay. Accounts on other servers
can't be deleted, because this server doesn't hold them, so suspend them instead.

People can delete their own account at `/settings/delete-account`, by entering their password. Admins can too, as long as
another admin remains.

Nobody can suspend or delete the server's last admin, including with a command, so [choose another](#admins) first. Admins also
can't suspend or delete themselves from the admin page or the API. A suspended admin doesn't count as another admin.

## Domain blocks
Admins can cut off another server at `/admin/domain-blocks`, or through the
[API](api.md). A block covers the domain's subdomains too, and applies straight away, with no restart. Nothing more is
accepted from those servers or sent to them, and their follows are removed. See
[`FEDERATION.md`](../FEDERATION.md#domain-blocks) for the details, and what a block doesn't stop.

Comments are only shown to admins, and the list of blocks isn't shown anywhere, but it isn't secret: anyone can find out
whether a domain is blocked, [as `FEDERATION.md` explains](../FEDERATION.md#domain-blocks). Removing a block doesn't bring back what was
removed, so followers on that server have to follow again.

## Job queue
Admins can see the background job queue at `/admin/db-scheduler`. It has a job for each activity received from another
server, a job for each activity being sent to another server's inbox, jobs that remove what's stored from a server that's
been blocked, jobs that remove what's stored for an account that's been deleted or suspended, and its own daily cleanup job. Each job shows what it's for, when it'll
next run, and its failures, and **History** shows why it failed. A job only holds IDs, so private posts aren't shown.

- **Retries:** A job that fails for a temporary reason, like a server being down, is tried again with longer and longer
  gaps, up to 6 hours apart, for 3 days.
- **Giving up:** After 3 days, or straight away if trying again can't help, a job is set aside for 30 days. It stays in the
  list as failed so that you can retry or delete it, and it's removed without running after that. If you retry one and it
  fails again, it's set aside for another 30 days. Activities are kept for 34 days, so retrying a job that's older than that
  can fail with "Its activity is gone", because there's nothing left to send or process.
- **Servers that keep failing:** After 3 failures in a row, the server waits before sending to that server again, starting
  at 1 minute and going up to 6 hours, or longer if the other server asks with `Retry-After`, up to the same limit. Jobs
  waiting for this aren't failing, so they show as scheduled. If you run several copies of the server, they share this.
- **Limits:** Each copy of the server sends at most 3 activities at a time to another server, leaving a connection for
  fetching its keys, so the real limit is 3 times the number of copies. Jobs waiting for their turn show as scheduled, and
  each copy starts about 3 of them a second for one server, so a large backlog for one server drains at about 180 a minute.
- **History:** Successful runs are kept for 7 days, and failed ones for 30.

Commands such as `create-user` never run jobs, so they're safe to use while the server is running.

## Rate limits
Logins and sign-ups are limited, and requests over a limit get a `429` response with a `Retry-After` header:

| Attempts                                         | Limit             |
|--------------------------------------------------|-------------------|
| Failed logins from one address                   | 20 per 15 minutes |
| Failed logins for one username from one address  | 5 per 15 minutes  |
| Failed logins for one username from any address  | 50 per hour       |
| Sign-ups from one address                        | 5 per hour        |

IPv6 addresses are counted by their /64 network. Successful logins don't count as failures, and sign-ups rejected by
validation don't count at all. The limits don't apply to `create-user`, and don't end existing login sessions. Someone
trying many passwords can make a username unavailable for logging in until the hour is up, including for its owner.

Each limit counts in fixed periods that start with the first attempt, so a burst around the end of a period can briefly
reach twice the limit. Counts are kept in memory, so restarting resets them, and each instance has its own.

If the server is behind a reverse proxy, it has to be [set up correctly](configuration.md#reverse-proxies). Otherwise
all clients can share one limit, or clients can get around the limits by making up their addresses.

To turn the limits off, because you enforce your own elsewhere, set `elysium.rate-limit.enabled=false`. This doesn't turn
off the [limit on inboxes](../FEDERATION.md#inboxes), which is always on. A limit elsewhere can't replace it, because it
only counts requests that make this server fetch a key.

**Warning:** These limits only slow down abuse from a small number of addresses. An attacker with many addresses can
spread out their attempts, and there's still no email confirmation, approval queue, or invite system for sign-ups. For
this reason, it's _strongly recommended_ to not use this software on a public facing server at this time.

## Login sessions

Login sessions are stored in the database, so they survive restarts, and if you run more than one instance, any of them can
serve any browser. A session ends after 30 minutes without a request, which you can change with
`server.servlet.session.timeout`, and each instance removes expired ones every minute. Restarting doesn't log anyone out, so
to end someone's sessions use [`reset-password`](#accounts-and-sign-ups). Only a hash of each session's ID is stored, so what's
in the database or a backup can't be used to take over a session. If you log PostgreSQL statements with their parameters, those
logs do contain the IDs, so keep them private.

Applications' access tokens are separate from sessions, and are stored in the database too.

Visitors who aren't logged in can have a session too, because login and sign-up forms keep their CSRF token in one. Each
visit from a browser without a cookie adds rows until it expires, and nothing limits how quickly, so if you're watching your
database's size, look at `spring_session`.
