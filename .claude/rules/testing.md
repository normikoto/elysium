---
paths:
  - "src/test/**"
---

# Testing

- Integration tests are `@SpringBootTest` with `@Import(TestcontainersConfiguration.class)`, which runs a real
  PostgreSQL. One container serves the whole run, and each Spring context gets an empty database of its own in it.
- `src/test/resources/config/application.properties` turns the login and sign-up rate limits off, because test classes
  share contexts and one address. `RateLimitIntegrationTests` turns them back on.
- The inbox's limit on key fetches is always on, so tests that make an inbox fetch keys should set their own address, as
  `InboxIntegrationTests` does.
- The sign-up page refuses to create the first account, and a test class's database can start empty. Call
  `TestAccounts.makeSureAnAccountExists` before a test signs up.
