# Elysium

Elysium is an experimental ActivityPub server built with Java and Spring Boot. It aims to work with Mastodon and other
common ActivityPub servers.

**Warning:** Elysium is experimental. Run it on a public server only if you are prepared to manage its limitations,
including [partial federation support](FEDERATION.md).

## Documentation
- [Installation](docs/installation.md) covers Docker Compose, systemd, and HTTPS.
- [Configuration](docs/configuration.md) covers settings, PostgreSQL, and reverse proxies.
- [Accounts and sign-ups](docs/accounts.md) covers account setup, moderation, and routine administration.
- [Using the API](docs/api.md) covers OAuth and differences from Mastodon.
- [Federation](FEDERATION.md) lists supported protocols and describes how servers interoperate.

## Local development
Run `./gradlew bootRun` to start Elysium at `http://localhost:8080` with the `dev` profile and PostgreSQL from
`compose.dev.yaml`. If you launch the app from your IDE or another tool, activate the `dev` profile yourself.
