# Job-scoped webhook and `bitbucketStatus` step

## Goals

1. Each Bitbucket webhook (workspace or repository level) invokes exactly one Jenkins job, selected by the webhook URL.
2. A Pipeline can publish several named commit statuses on one commit.

## Non-goals

- Global (repository-matching) routing and multibranch webhook handling are removed, not replaced.
- Per-job webhook secrets: the single global secret is kept.
- Reading files from Bitbucket.

## 1. Job-scoped webhook

Endpoint: `POST /bitbucket-webhook/job/<fullName>`, e.g. `/bitbucket-webhook/job/folder/my-job`.

Flow in `BitbucketWebhookAction`:

1. Reject non-POST (405), oversized bodies (413), bad/missing signature (401) exactly as today.
2. Parse the payload (`EventParser`); an empty event list answers 200 `Ignored`.
3. Resolve `<fullName>` (URL-decoded) with `Jenkins.getItemByFullName(name, Job.class)` under `ACL.SYSTEM2`.
   Unknown job, or a job without a `BitbucketTrigger`, answers 404.
4. If `trigger.accepts(event)`, call `trigger.fire(event)`; answer 202 `Accepted`.
   Repository matching, branch filters, draft/fork/duplicate rules are unchanged.

Any other path, including `/bitbucket-webhook/` itself, answers 404.

Removed: `TriggerEventDispatcher`, `MultibranchEventDispatcher`, `BitbucketHeadEvent`, `JobMatcher`, and the
`EventDispatcher` extension point if nothing else uses it. Repo-key helpers stay if still referenced.

Compatibility: breaking. The commit carries a `BREAKING CHANGE:` footer and README documents the new URL.

## 2. `bitbucketStatus` step

```groovy
bitbucketStatus(name: 'lint', state: 'INPROGRESS', description: 'running')
bitbucketStatus(name: 'lint', state: 'SUCCESSFUL')
```

- Parameters: `name` (required), `state` (`INPROGRESS|SUCCESSFUL|FAILED|STOPPED`, required), `description`, `url`
  (defaults to the run URL).
- Implemented as a synchronous `Step` in the plugin (`workflow-step-api`).
- Target repo and commit come from the same resolution `StatusReporter` already uses (trigger cause/action, then Git SCM).
  If it cannot be resolved the step logs a warning and continues; it never fails the build.
- Key: `statusKey(jobFullName + "/" + name)`; display name `Jenkins: <job> / <name>`. Re-posting the same `name` updates it.
- Credentials resolve like the automatic status (trigger `credentialsId`, then global default).
- The automatic per-job build status is unchanged and keeps reporting alongside step statuses.

Refactor: extract target/auth resolution from `StatusReporter` into a shared helper used by both.

## Testing

- `BitbucketWebhookActionTest`: job URL fires only that job; other matching jobs untouched; root path, unknown job and
  trigger-less job give 404; bad signature still 401.
- Step test: posts the expected key/name/state to a stub Bitbucket; unresolved target does not fail the build; state validation.
- Existing `StatusReporterTest`/`EndToEndTest` updated for the new URL and kept green (`mvn verify`).

## E2E check

Webhook URL `https://<host>/jenkins/bitbucket-webhook/job/bb-e2e` (repo or workspace hook, Push). Push `e2e/test`
to trigger; other branches ignored; three `bitbucketStatus` calls show three statuses on the commit.
