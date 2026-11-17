# Agent instructions

## Claim GitHub issues before starting work

When taking responsibility for a GitHub issue, **post a comment on that issue before starting implementation** that links directly to the current agent session or conversation. State explicitly that you are working on the issue so other agents can see it is claimed.

1. Check the issue's most recent comments and linked pull requests for an existing active claim before taking it. If another agent is already working on it, do not independently implement the same issue; coordinate or select a different issue.
2. When unclaimed, immediately comment with a direct session URL (for example, a ChatGPT conversation or agent-run link) and indicate that work is in progress. Do not substitute a generic profile or repository URL for the actual session.
3. If you stop working without completing the issue, add a comment releasing the claim. When you open a PR or complete the issue, comment with the PR link and status so the next agent knows what remains.

Issue comments are the shared coordination record; do not assume an issue is free merely because it has no assignee.

## Engineering principles

- **Prefer strong types over bare strings or numbers.** Represent domain concepts with dedicated types (such as value classes, enums, or sealed types) instead of interchangeable primitive values. Make units, identifiers, and other important distinctions explicit.
- **Parse, don't just validate.** At input boundaries, parse untrusted values into well-typed domain objects that carry their invariants. Do not validate primitive inputs and then continue passing those primitives around where they can become invalid again.
- **Make illegal states unrepresentable.** Design APIs and data models so invalid combinations cannot be constructed, using types, constrained constructors, and algebraic data types where appropriate.
- **Prefer composition over inheritance.** Build behavior from small cooperating components and explicit interfaces rather than deep inheritance hierarchies.
- **Keep files under 1,000 lines of code.** Split a file by coherent responsibility before it reaches 1,000 LOC; do not work around the limit by cramming unrelated code together or obscuring formatting.
