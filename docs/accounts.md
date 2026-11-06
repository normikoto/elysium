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

People can't change their own passwords yet, and sign-ups have no email confirmation. To replace someone's password, run
`reset-password alice`. This also logs them out everywhere and revokes their applications' access, so they'll need to
authorize them again.

## Admins
The server's first account becomes an admin, and later accounts are regular users. If sign-ups are turned on, `/signup`
refuses to create the first account until `create-user` has been run, so that a stranger can't become the admin.

If your server had accounts before this role was added, they stay regular users, so choose an admin with `set-role`. It
also removes the role:
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
don't come back if the suspension ends. To cut off a whole server, use a [domain block](#domain-blocks).

**Deleting a local account** can't be undone. Its posts and follows are removed, and other servers are asked to remove it
too, which they may not do. Its username can't be used again, because other servers can still know it. Accounts on other
servers can't be deleted, so suspend them instead.

People can delete their own account at `/settings/delete-account`, with their password.

Nobody can suspend or delete the server's last admin, so [choose another](#admins) first. Admins also can't suspend or delete
themselves from the admin page or the API.

## Domain blocks
Admins can cut off another server at `/admin/domain-blocks`, or through the [API](api.md). A block covers the domain's
subdomains too, and applies straight away. Nothing more is accepted from those servers or sent to them, and their follows are
removed. Removing a block doesn't bring those back, so followers on that server have to follow again. See
[`FEDERATION.md`](../FEDERATION.md#domain-blocks) for what a block doesn't stop.

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

## Rate limits
Logins and sign-ups are limited, and requests over a limit get a `429` response:

| Attempts                                         | Limit             |
|--------------------------------------------------|-------------------|
| Failed logins from one address                   | 20 per 15 minutes |
| Failed logins for one username from one address  | 5 per 15 minutes  |
| Failed logins for one username from any address  | 50 per hour       |
| Sign-ups from one address                        | 5 per hour        |

IPv6 addresses are counted by their /64 network. Someone trying many passwords can stop a username from logging in until the
hour is up, including its owner. Counts are kept in memory, so restarting resets them, and each copy of the server counts on
its own.

If the server is behind a reverse proxy, it has to be [set up correctly](configuration.md#reverse-proxies). Otherwise all
clients can share one limit, or clients can get around the limits by making up their addresses.

To turn the limits off, because you enforce your own elsewhere, set `elysium.rate-limit.enabled=false`. This doesn't turn off
the [limit on inboxes](../FEDERATION.md#inboxes), which can't be replaced by one elsewhere.

**Warning:** These limits only slow down abuse from a small number of addresses, and there's still no email confirmation,
approval queue, or invite system for sign-ups. For this reason, it's _strongly recommended_ to not use this software on a
public facing server at this time.

## Login sessions
Login sessions are stored in the database, so restarting doesn't log anyone out, and with more than one copy of the server,
any of them can serve any browser. A session ends after 30 minutes without a request, which you can change with
`server.servlet.session.timeout`. To end someone's sessions, use `reset-password`.

Only a hash of each session's ID is stored, so the database or a backup can't be used to take over a session. If you log
PostgreSQL statements with their parameters, those logs do contain session IDs, so keep them private. Visitors who
aren't logged in also get sessions, so if you're watching your database's size, look at the `spring_session` table.
