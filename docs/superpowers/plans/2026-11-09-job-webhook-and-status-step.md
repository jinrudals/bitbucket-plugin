# Job-scoped webhook and `bitbucketStatus` step Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let each Bitbucket webhook call exactly one Jenkins job via its URL, and let a Pipeline publish several named commit statuses.

**Architecture:** `BitbucketWebhookAction` resolves the job from the URL path and fires only that job's `BitbucketTrigger`; global repo-matching routing and multibranch webhook handling are deleted. A shared `StatusPoster` (extracted from `StatusReporter`) resolves repo/commit/auth for a run and posts a `CommitStatus`; both `StatusReporter` and the new `bitbucketStatus` step use it.

**Tech Stack:** Java 17, Jenkins plugin (Stapler, workflow-step-api), JUnit 5 + JenkinsRule + WireMock. Build/verify: `mvn verify`.

**Spec:** `docs/superpowers/specs/2026-11-09-job-webhook-and-status-step-design.md`

## Global Constraints

- Webhook URL is `POST /bitbucket-webhook/job/<fullName>` (fullName URL-decoded, folders separated by `/`).
- Any other path, including `/bitbucket-webhook/`, answers 404; unknown job or job without `BitbucketTrigger` answers 404.
- Existing 405 / 413 / 401 / 400 / 200 `Ignored` / 202 `Accepted` behavior and global-secret signature check are unchanged.
- Step `state` is one of `INPROGRESS|SUCCESSFUL|FAILED|STOPPED`; status key is `StatusReporter.statusKey(jobFullName + "/" + name)`; display name `Jenkins: <job> / <name>`.
- The automatic per-job build status keeps working unchanged (key = `statusKey(jobFullName)`, name `Jenkins: <job>`).
- A step that cannot resolve target/credentials warns in the build log and never fails the build.
- Commits follow Angular convention; the webhook commit carries a `BREAKING CHANGE:` footer and the `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>` trailer.
- Work only in worktree `.worktrees/job-webhook-status` (branch `feat/job-webhook-status`).

## Review Focus

- Job name with folders/spaces/percent-encoding (`folder/my%20job`): resolves to the right job.
- Event repo differs from the trigger's `repository`: job is not fired (still 202, no build).
- Job URL that points at a job with trigger but a disabled/excluded branch: 202, no build.
- Step called with an invalid `state` or blank `name`: fails fast with a clear error (this is a usage error, not a Bitbucket failure).
- Step called when Bitbucket answers 4xx/5xx: build result unchanged.
- Long job+name key over 40 chars: hashed deterministically, two names stay distinct.

---

### Task 1: Job-scoped webhook, remove global routing

**Files:**
- Modify: `src/main/java/io/jenkins/plugins/bitbucketcloud/webhook/BitbucketWebhookAction.java`
- Modify: `src/main/java/io/jenkins/plugins/bitbucketcloud/routing/JobMatcher.java`
- Delete: `routing/TriggerEventDispatcher.java`, `routing/MultibranchEventDispatcher.java`, `routing/BitbucketHeadEvent.java`, `webhook/EventDispatcher.java` (all under `src/main/java/io/jenkins/plugins/bitbucketcloud/`)
- Delete tests: `routing/MultibranchEventDispatcherTest.java`
- Modify tests: `webhook/BitbucketWebhookActionTest.java`, `routing/JobMatcherTest.java`, `EndToEndTest.java`
- Modify: `README.md`

**Interfaces:**
- Produces: `JobMatcher.findTrigger(Job<?, ?> job, RepoKey repo): Optional<BitbucketTrigger>` — the job's `BitbucketTrigger` whose explicit `repository` equals `repo`, or (no explicit repository) whose job SCM remote matches `repo`; empty otherwise. Replaces `findTriggers(RepoKey)` (delete it).
- Produces: `BitbucketWebhookAction` job path handling: `/bitbucket-webhook/job/<fullName>`.

- [ ] **Step 1: Write failing tests in `BitbucketWebhookActionTest`** (replace the `@TestExtension` dispatcher approach with real triggers on freestyle jobs, helper `post(String path, String body)` taking the path after `bitbucket-webhook/`):
  - `jobUrlFiresOnlyThatJob`: two freestyle jobs with `BitbucketTrigger` for the same `repository`; POST signed push to `job/a` → job `a` gets one build, job `b` none; response 202.
  - `rootPathIs404`: signed push to `""` → 404.
  - `unknownJobAndTriggerlessJobAre404`.
  - `folderAndEncodedNameResolve`: job `folder/my job` reachable via `job/folder/my%20job`.
  - `repoMismatchOrBranchFilterFiresNothing`: 202 and no build.
  - Keep existing 405/413/401/400/200 tests, pointed at `job/<existing job>`.
- [ ] **Step 2: Run** `mvn -q test -Dtest=BitbucketWebhookActionTest` — Expected: FAIL.
- [ ] **Step 3: Implement** in `BitbucketWebhookAction.doDynamic`: after the existing signature/parse steps, strip the `/job/` prefix from `req.getRestOfPath()`; reject any other rest-of-path with 404 (check this *after* the signature check so unauthenticated callers cannot probe job names — 401 comes first). Resolve with `Jenkins.get().getItemByFullName(name, Job.class)` under `ACL.as2(ACL.SYSTEM2)`; 404 if null. For each event, `JobMatcher.findTrigger(job, RepoKey.fromFullName(event.repoFullName()))`; if present and `accepts(event)`, call `fire(event)` asynchronously via the existing `SecurityContextExecutorService` pattern. Empty trigger result for the job → 404 only when the job has no `BitbucketTrigger` at all; repo mismatch is a silent 202.
- [ ] **Step 4: Refactor `JobMatcher`**: replace `findTriggers(RepoKey)` with `findTrigger(Job, RepoKey)` reusing the existing `collect`/`matches` logic; delete the four classes listed above and their tests; update `JobMatcherTest` to the new method.
- [ ] **Step 5: Update `EndToEndTest`**: webhook helper posts to `bitbucket-webhook/job/<jobName>`; delete `multibranchIndexesAndBuildsNewBranchFromPush`; `unknownRepositoryTriggersNothing` keeps asserting no build.
- [ ] **Step 6: Update `README.md`**: webhook URL becomes `https://<jenkins>/bitbucket-webhook/job/<job fullName>` (one webhook per job, repo or workspace level); remove the multibranch paragraph and the "Events for repositories that no job…" sentence.
- [ ] **Step 7: Run** `mvn -q test` — Expected: all PASS.
- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(webhook)!: route webhooks to a single job by url

BREAKING CHANGE: /bitbucket-webhook/ no longer routes by repository; use
/bitbucket-webhook/job/<fullName>. Multibranch webhook handling is removed.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 2: Extract `StatusPoster` from `StatusReporter`

**Files:**
- Create: `src/main/java/io/jenkins/plugins/bitbucketcloud/status/StatusPoster.java`
- Modify: `src/main/java/io/jenkins/plugins/bitbucketcloud/status/StatusReporter.java`
- Test: `src/test/java/io/jenkins/plugins/bitbucketcloud/status/StatusReporterTest.java` (must stay green unchanged)

**Interfaces:**
- Produces: `StatusPoster.post(Run<?, ?> run, String key, String name, String state, String description, String url): boolean` — resolves auth (trigger `credentialsId`, then global default) and target (existing `target(run)` logic incl. multibranch), posts `new CommitStatus(key, name, state, url, description)` via `BitbucketClient`; returns false (no throw) when auth or target is missing or on any non-fatal failure. Package-visible `statusKey` stays on `StatusReporter`.
- Consumes: existing `AuthResolver`, `BitbucketClient.postCommitStatus`, `BitbucketGlobalConfig`.

- [ ] **Step 1: Run baseline** `mvn -q test -Dtest=StatusReporterTest` — Expected: PASS.
- [ ] **Step 2: Move** `resolveAuth`, `target`, `directTarget`, `multibranchTarget`, `Target` and the `BitbucketClient` call into `StatusPoster` as the single `post` method above; `StatusReporter.report` and `MultibranchStart` call `StatusPoster.post(run, statusKey(fullName), "Jenkins: " + fullName, state, description, run.getAbsoluteUrl())`. `MultibranchStart` still needs "direct target absent, multibranch target present", so expose `StatusPoster.hasMultibranchOnlyTarget(Run<?, ?>): boolean`.
- [ ] **Step 3: Run** `mvn -q test -Dtest=StatusReporterTest` — Expected: PASS (behavior unchanged).
- [ ] **Step 4: Commit** `refactor(status): extract StatusPoster for reuse` (with Co-authored-by trailer).

### Task 3: `bitbucketStatus` Pipeline step

**Files:**
- Create: `src/main/java/io/jenkins/plugins/bitbucketcloud/status/BitbucketStatusStep.java`
- Modify: `pom.xml` (add `org.jenkins-ci.plugins.workflow:workflow-step-api` as a compile dependency only if the module does not compile without it)
- Create test: `src/test/java/io/jenkins/plugins/bitbucketcloud/status/BitbucketStatusStepTest.java`
- Modify: `README.md`

**Interfaces:**
- Consumes: `StatusPoster.post(...)` (Task 2), `StatusReporter.statusKey(String)`.
- Produces: Pipeline step `bitbucketStatus(String name, String state, String description = "", String url = run URL)`; `@DataBoundConstructor BitbucketStatusStep(String name, String state)`, `@DataBoundSetter setDescription(String)`, `@DataBoundSetter setUrl(String)`; `@Symbol("bitbucketStatus")` on its `StepDescriptor` (requires `Run` and `TaskListener` contexts).

- [ ] **Step 1: Write failing tests** (WireMock stub on commit statuses endpoint, trigger-created build action providing repo/commit as in `StatusReporterTest.setup`, Pipeline job script):
  - `threeNamedStatusesPostThreeKeys`: script calls the step for `lint`, `test`, `deploy`; assert three POSTs with keys `<job>/lint`, `<job>/test`, `<job>/deploy`, name `Jenkins: <job> / lint`, correct states, and the automatic job status is still posted.
  - `sameNameTwiceUpdatesSameKey`: `INPROGRESS` then `SUCCESSFUL` for `lint` → two POSTs, same key.
  - `invalidStateOrBlankNameFailsBuild`: `state: 'BOGUS'` and `name: ''` → build result FAILURE with a message naming the bad argument.
  - `bitbucketErrorDoesNotFailBuild`: stub 500 → build SUCCESS, warning in log.
  - `unresolvedTargetWarnsAndContinues`: manual build without trigger action → SUCCESS, warning in log, no POST.
  - `longKeyIsHashedAndDistinct`: two long names produce two distinct keys, each ≤ 40 chars.
- [ ] **Step 2: Run** `mvn -q test -Dtest=BitbucketStatusStepTest` — Expected: FAIL (step not defined).
- [ ] **Step 3: Implement** `BitbucketStatusStep extends Step` with a `SynchronousNonBlockingStepExecution<Void>` that validates `name` (non-blank) and `state` (the four values), computes key `StatusReporter.statusKey(job.getFullName() + "/" + name)`, calls `StatusPoster.post`, and on `false` prints a warning to the `TaskListener`; never throws for posting problems.
- [ ] **Step 4: Run** `mvn -q test -Dtest=BitbucketStatusStepTest` — Expected: PASS.
- [ ] **Step 5: Document** the step and per-job webhook E2E recipe in `README.md` (step parameters, that the automatic status is kept, example with three statuses).
- [ ] **Step 6: Run** `mvn verify` — Expected: BUILD SUCCESS (spotless/spotbugs included; run `mvn spotless:apply` first if formatting fails).
- [ ] **Step 7: Commit** `feat(status): add bitbucketStatus pipeline step` (with Co-authored-by trailer).

---

## Self-review

- Spec coverage: §1 → Task 1; §2 → Tasks 2–3; Testing section → tests in each task; E2E recipe → README in Tasks 1 and 3.
- Types: `StatusPoster.post` signature identical in Tasks 2 and 3; `JobMatcher.findTrigger` defined in Task 1 only.
- Proportion: plan states decisions (signatures, assertions, constants) and leaves bodies to the implementer.
