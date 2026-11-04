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
`reset-password alice`. It also revokes the access of all their applications, so they'll need to authorize them again.
Anyone already logged in through a browser stays logged in until the server restarts, and could authorize an application
until then, so restart the server if the account may be compromised.

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
compromised, remove the role first, because [`reset-password`](#accounts-and-sign-ups) doesn't end their browser sessions.
Those sessions stay logged in without admin access, and giving the role back would restore it to them, so restart the
server before you do. You can remove the last admin, and use the same command to choose another.

Applications only get admin scopes from an admin. A regular user can't approve them, and they have no effect on app
tokens, which don't belong to any user, even if the application registered with them. Changing an account's role also
revokes its applications that have admin scopes, along with the rest of their access to that account, so they'll need to
be authorized again.

## Job queue
Admins can see the background job queue at `/admin/db-scheduler`. The server doesn't send or receive activities yet, so
for now the queue only has its own daily cleanup job. Once federation work runs here, each job shows what it's for, when
it'll next run, and its failures, and **History** shows why it failed. A job only holds IDs, so private posts aren't shown.

- **Retries:** A job that fails for a temporary reason, like a server being down, is tried again with longer and longer
  gaps, up to 6 hours apart, for 3 days.
- **Giving up:** After 3 days, or straight away if trying again can't help, a job is set aside for 30 days. It stays in the
  list as failed so that you can retry or delete it, and it's removed without running after that. If you retry one and it
  fails again, it's set aside for another 30 days.
- **Servers that keep failing:** After 3 failures in a row, the server waits before sending to that server again, starting
  at 1 minute and going up to 6 hours, or longer if the other server asks with `Retry-After`, up to the same limit. Jobs
  waiting for this aren't failing, so they show as scheduled. If you run several copies of the server, they share this.
- **Limits:** Each copy of the server sends at most 4 requests at a time to another server, so the real limit is 4 times
  the number of copies.
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

To turn the limits off, because you enforce your own elsewhere, set `elysium.rate-limit.enabled=false`.

**Warning:** These limits only slow down abuse from a small number of addresses. An attacker with many addresses can
spread out their attempts, and there's still no email confirmation, approval queue, or invite system for sign-ups. For
this reason, it's _strongly recommended_ to not use this software on a public facing server at this time.

## Login sessions

Login sessions are stored in memory, so restarting logs people out of the login page. Applications' access tokens are
stored in the database and aren't affected. If you run more than one instance, your load balancer needs to keep each
browser on the same instance.
