<!-- CODEGRAPH_START -->
## CodeGraph

In repositories indexed by CodeGraph (a `.codegraph/` directory exists at the repo root), reach for it BEFORE grep/find or reading files when you need to understand or locate code:

- **MCP tool** (when available): `codegraph_explore` answers most code questions in one call — the relevant symbols' verbatim source plus the call paths between them, including dynamic-dispatch hops grep can't follow. Name a file or symbol in the query to read its current line-numbered source. If it's listed but deferred, load it by name via tool search.
- **Shell** (always works): `codegraph explore "<symbol names or question>"` prints the same output.

If there is no `.codegraph/` directory, skip CodeGraph entirely — indexing is the user's decision.
<!-- CODEGRAPH_END -->

@C:\Users\hyun\.codex\RTK.md

## Mandatory Git workflow

For every ChatGPT/Codex task that modifies repository files, regardless of which GitHub tool, connector, writer app, MCP, shell, or fallback path is used:

1. Never modify or push directly to `main`.
2. Create or reuse a `chatgpt/*` branch from the latest `main`.
3. Commit and push all requested changes to that branch.
4. Always create or update a pull request targeting `main`.
5. Never finish a modification task without reporting the pull request number and URL.
6. If required checks pass and repository rules permit it, squash-merge the pull request.
7. If merge is blocked, leave the pull request open and report the exact blocker.
8. A writer/tool failure does not waive this workflow; use another available GitHub method and still complete the branch -> commit -> push -> pull request sequence.

When the Web ChatGPT Git writer app is selected, Web ChatGPT is the primary autonomous coding agent.
It must translate the user's natural-language request into a complete implementation without waiting
for another orchestrator. It must read this file, investigate and reproduce material findings, change
every required source and directly related test, critically inspect the complete diff, run all applicable
checks, fix failures, commit and push only chatgpt/*, create or update a pull request, and squash-merge it
after required checks pass. It must not impose arbitrary file-count or scope limits.
It must never push directly to main, force-push, delete branches, expose secrets, weaken tests,
or modify protected repository, workflow, credential, or writer files.
