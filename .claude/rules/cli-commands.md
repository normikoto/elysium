---
paths:
  - "src/main/java/dev/biribiri/elysium/**/*Command.java"
  - "src/main/java/dev/biribiri/elysium/CommandRunner.java"
  - "src/main/java/dev/biribiri/elysium/ElysiumApplication.java"
---

# CLI commands

`ElysiumApplication.main` treats any argument that isn't an option as a command, and hands it to `CommandRunner`.

- To add one, write a picocli `@Command` class anywhere, implementing `Callable<Integer>`, with a no-argument
  constructor and `@Autowired` methods for its dependencies.
- The command line is parsed before Spring starts.
- Commands run with no web server and no job worker, so they're safe to run alongside the server.
