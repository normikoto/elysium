---
paths:
  - "README.md"
  - "CHANGELOG.md"
  - "FEDERATION.md"
  - "docs/**/*.md"
  - "federation/README.md"
---

# Writing documentation

Each document has one audience. Keep each fact short, put it in one place, and link to it from elsewhere. Describe only
what its readers can observe or have to do, and leave out how the server is built: class names, tables, job internals,
the order things happen in inside the server, exact retention times, and edge cases its readers won't notice.
`federation/README.md` is the exception, since it's for contributors.

## Audiences

- **`README.md`, `CHANGELOG.md`, and `docs/` other than `docs/api.md`** are for instance admins. Write only what an admin
  needs to install, configure, operate, or understand the server's visible behavior: settings, commands, what happens to
  their users and data, limits they may hit, and security pitfalls to avoid.
- **`CHANGELOG.md`** gets every user-facing change in its `[Unreleased]` section, with upgrade steps when an operator has
  to do something.
- **`FEDERATION.md`** follows FEP-67ff, so it's for developers of other fediverse servers as well as admins. Describe what
  another server can observe and has to know to interoperate: endpoints, content types, response codes, which activities
  are sent and accepted and what they look like, how they're addressed, IDs, signature requirements, limits, ordering and
  repeat behavior, and differences from common implementations. Link to `docs/` for admin steps rather than repeating
  them.
- **`docs/api.md`** is for developers of client applications. List every difference from Mastodon's API that a client
  could run into: missing endpoints, parameters, and fields, different defaults, limits, validation that refuses what
  Mastodon accepts, and different error responses.

The rest of this file is written for the admin docs. Warnings, examples, and consistent wording apply to every document.

## Warnings

- If an action can't be undone, or part of it can't, say so in the paragraph's first sentence, not partway through.
- `**Warning:**` at the start of a paragraph is the only callout. Use it only for data loss, a security exposure, or
  breaking federation, so that it still stands out.

## Settings

- Describe each setting in this order: "Required." or "Optional.", what it does, the values it accepts, "Defaults to"
  with the value in backticks, and what changing it does to existing data. Every optional setting states its default.
- Give an example for durations, sizes, and lists, written as the setting accepts it, such as `4MB` or `12h`.
- Keep a table row to those parts and at most one sentence about its effects. Explain more in a section of the page, and
  link to it from the row.

## Effects

- When a feature has several effects, list them as bullets, each saying what happens to users or their data, such as "Its
  posts and profile are hidden."
- Say what a feature doesn't protect against, where an admin could assume it does.

## Commands

- Where a command differs by how the server is run (Docker Compose, as a service, from source), show each form.
- Use one placeholder style across the docs, and use realistic names such as `alice` where a real value reads better.
- Never put a password or other secret on the command line in an example. Show how to enter or pipe it instead.
- A command that deletes data follows the warning rules above.

## Procedures

- End each install or setup procedure with how to tell it worked: what to open or run, and what the admin should see.

## Wording

- Use one word for each thing. In the admin docs, say "post" rather than "status", except when naming a setting or an
  API field.
- Write where something is in the UI as its path, such as `/admin/accounts`.
- Give examples a realistic value and, where it helps, a reason someone would choose it, rather than `foo` or a value that
  only repeats the default.

## Avoid

- Notes on which version added or changed something, until there are releases to refer to. `CHANGELOG.md` holds that.
- Pasting a command's `--help` output, which goes out of date and repeats itself.
- Jokes and capitals for emphasis.
