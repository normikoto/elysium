---
paths:
  - "src/main/java/dev/biribiri/elysium/activitypub/**"
  - "FEDERATION.md"
  - "federation/**"
---

# Federation

`activitypub`'s classes document how they work. These are the rules that a change can break without a test noticing.
The rules that reach into other modules, such as account state and cleanup, are in `AGENTS.md`.

## Inbound

- Who an activity is for comes from the activity, never from the inbox's URL.
- Checks on who's speaking for an `id` run before `InboundActivities.accept`, where the first activity stored under an ID
  wins.
- Each activity type has one `InboundActivityHandler`, and another type of `Undo` extends `UndoHandler`.
- Anything a handler reads from a body and then stores hasn't been through the inbox's checks, and a handler has to check
  any actor or object it fetches or stores other than the activity's actor.
- Recipients come from `LocalRecipients`, and parsing goes through `ActivityJson` and the parsers that use it.

## Signatures

- `SignatureVerifier.verify` needs a `KeyFetchBudget`, so every caller decides who pays for key fetches.
- Signer order is explained in `activitypub/signature/package-info.java`.

## Outbound

- A listener in `activitypub.outbound` builds the activity from an event a core module published.
- Listeners have to be safe to repeat and mustn't fail for what's since been deleted, because `EventRetries` reruns
  failed ones.
- Build `Note`s with `Notes`, so fetched and delivered ones match.

## Domain blocks

- Blocks are decided in `moderation.DomainBlocks` and enforced in `activitypub`.

## Outbound HTTP

- Use `activitypub.http.FederationHttpClient`, which refuses private addresses.
- The lab uses `elysium.federation.allowed-private-networks`, and `federation/README.md` rules out an on/off switch or
  plain HTTP.
