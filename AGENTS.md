# AGENTS.md

Guidance for AI agents working in this repository.

## Project

A Jenkins plugin for Bitbucket Cloud, modeled on the Jenkins GitLab Plugin. See `README.md`.

## Documentation

- Before starting work, read the relevant documents under `docs/harness/**/*.md`. They are the source of truth for architecture, conventions, and workflows.
- All documents intended for agents (this file and everything under `docs/harness/`) MUST be written in English.

## Commit convention

Follow the [Angular commit convention](https://github.com/angular/angular/blob/main/contributing-docs/commit-message-guidelines.md):

```
<type>(<optional scope>): <short summary>

<optional body>

<optional footer>
```

- Types: `feat`, `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`.
- Summary: imperative, present tense, lowercase, no trailing period.
- Breaking changes: add a `BREAKING CHANGE:` footer.
