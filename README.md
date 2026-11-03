# Elysium

Experimental ActivityPub server using Java and Spring Boot. Aim is general compatibility with Mastodon and other common
AP implementations.

**There are known limitations and pitfalls when it comes to being production-ready right now, please don't run this on a
public server unless you really know what you're doing.** For example,
[sign-ups have no email confirmation](docs/accounts.md).

## Documentation
- [Installation](docs/installation.md): building and running the server, with Docker Compose or as a systemd service
- [Configuration](docs/configuration.md): settings, the database, and reverse proxies
- [Accounts and sign-ups](docs/accounts.md): creating accounts, resetting passwords, and login sessions
- [Using the API](docs/api.md): OAuth, statuses, and how they differ from Mastodon
- [Federation](FEDERATION.md): which federation protocols and standards are implemented so far

## Local development
`./gradlew bootRun` activates the `dev` profile, which sets the base URL to `http://localhost:8080`, and starts
PostgreSQL through `compose.dev.yaml`. If you run the application another way, such as from your IDE, activate the `dev`
profile yourself.
