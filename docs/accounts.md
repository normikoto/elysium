# Accounts and sign-ups

Sign-ups are currently disabled by default, so create your first account with the `create-user` command:
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

**Warning:**  The server has no rate limiting for logins or registrations, aside from a limit to how many passwords the
server hashes at one time. For this reason, it's _strongly recommended_ to not use this software on a public facing
server at this time. If you're daring, you can add rate limiting to your reverse proxy configuration to mitigate
this problem.

Login sessions are stored in memory, so restarting logs people out of the login page. Applications' access tokens are
stored in the database and aren't affected. If you run more than one instance, your load balancer needs to keep each
browser on the same instance.
