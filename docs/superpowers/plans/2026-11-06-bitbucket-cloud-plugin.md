# Bitbucket Cloud Jenkins Plugin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Jenkins plugin that triggers builds from Bitbucket Cloud webhooks (including workspace-level hooks) and reports build status back as commit statuses.

**Architecture:** A single `UnprotectedRootAction` receives webhooks, verifies the HMAC signature, converts the payload to immutable domain events, and asynchronously matches jobs by normalized repository URL. Triggers, env-var actions, and a `RunListener` status reporter share a `BitbucketBuildAction` that carries repo and commit info. Auth is hidden behind a `BitbucketAuth` interface.

**Tech Stack:** Java 17, Maven, Jenkins LTS 2.452.x (plugin parent POM), scm-api, credentials, plain-credentials, git plugin, workflow-job/cps/multibranch (tests), JUnit 5, `jenkins-test-harness` (`JenkinsRule`), WireMock.

**Spec:** `docs/superpowers/specs/2026-11-06-bitbucket-cloud-plugin-design.md`

## Global Constraints

- Package root: `io.jenkins.plugins.bitbucketcloud`; artifactId `bitbucket-cloud`
- Java 17, Jenkins baseline 2.452.x or newer
- Webhook endpoint URL name: `bitbucket-webhook` (`/bitbucket-webhook/`)
- Signature header `X-Hub-Signature` (`sha256=<hex>`, HMAC-SHA256); event header `X-Event-Key`
- Supported events: `repo:push`, `pullrequest:created`, `pullrequest:updated` only
- Env vars: `bitbucketSourceBranch`, `bitbucketTargetBranch`, `bitbucketPullRequestId`, `bitbucketRepoFullName`, `bitbucketCommitHash`, `bitbucketActor`
- Commit status: `POST /2.0/repositories/{workspace}/{repo}/commit/{hash}/statuses/build`, key = job full name, name = `Jenkins: <job>`, states `INPROGRESS`/`SUCCESSFUL`/`FAILED`/`STOPPED`
- No dependency on Bitbucket Branch Source; no secrets or tokens in logs
- Out of scope: comment/tag triggers, `bitbucketStatus` step, PR approve/comment steps, Data Center

## Review Focus

- Webhook body is malformed JSON or missing `repository` → 400, never 500 (Task 2, 8)
- Push with multiple changes, a branch deletion (`new: null`), or a tag push → no crash; deletions and tags are ignored (Task 2)
- Workspace hook delivers events for repos no job uses → 200/202, no error, no build (Task 10, 13)
- Same commit arrives as `repo:push` then `pullrequest:updated` → one build when dedupe enabled (Task 9, 13)
- Repo URL variants (`https://user@bitbucket.org/ws/Repo.git`, `git@bitbucket.org:ws/repo`, trailing slash, mixed case) → all match one key (Task 3)
- Status API returns 429/5xx/401/network error → build result unchanged, bounded retries (Task 6, 12)
- No webhook secret configured → all requests rejected with 401 unless explicit allow-unsigned option is on (Task 8)

---

### Task 1: Project scaffold

**Files:**
- Create: `pom.xml`, `src/test/java/io/jenkins/plugins/bitbucketcloud/PluginSmokeTest.java`, `README.md`

**Interfaces:**
- Produces: buildable `hpi` project; JUnit 5 + `@WithJenkins` test support; WireMock test dependency.

- [ ] **Step 1: Write failing test** `PluginSmokeTest.jenkinsStarts(JenkinsRule j)` asserting `j.jenkins.getPluginManager().getPlugin("bitbucket-cloud") != null`.
- [ ] **Step 2: Run** `mvn -q test -Dtest=PluginSmokeTest` → FAIL (no pom).
- [ ] **Step 3: Create `pom.xml`** with parent `org.jenkins-ci.plugins:plugin` (current 4.x), `jenkins.baseline=2.452`, `jenkins.version=${jenkins.baseline}.1`, `java.level=17`, BOM `bom-${jenkins.baseline}.x`, dependencies: `scm-api`, `credentials`, `plain-credentials`, `git`, `jackson2-api`, `workflow-job`, `workflow-cps`, `workflow-multibranch`, `wiremock` (test).
- [ ] **Step 4: Run** `mvn -q test -Dtest=PluginSmokeTest` → PASS.
- [ ] **Step 5: Commit** `feat: scaffold plugin project`

### Task 2: Domain events and payload parser

**Files:**
- Create: `event/BitbucketEvent.java`, `event/PushEvent.java`, `event/PullRequestEvent.java`, `event/EventParser.java`, `event/InvalidPayloadException.java`
- Test: `event/EventParserTest.java`, fixtures `src/test/resources/payloads/{push,push-multi,push-delete,push-tag,pr-created,pr-updated,pr-draft,pr-fork}.json` (based on Bitbucket's documented samples)

**Interfaces:**
- Produces:
  - `sealed interface BitbucketEvent permits PushEvent, PullRequestEvent` with `String repoFullName()`, `String actor()`, `String commitHash()`.
  - `record PushEvent(String repoFullName, String actor, String branch, String commitHash)`
  - `record PullRequestEvent(String repoFullName, String actor, String type /*created|updated*/, String prId, String sourceBranch, String targetBranch, String commitHash, boolean draft, boolean fromFork)`
  - `EventParser.parse(String eventKey, String body) -> List<BitbucketEvent>`: one `PushEvent` per branch change; empty list for unsupported event keys, branch deletions, and tag pushes; throws unchecked `InvalidPayloadException` on malformed JSON or missing repository.

- [ ] **Step 1: Write failing tests** in `EventParserTest`: `parsesPush`, `pushWithMultipleChangesYieldsOneEventPerBranch`, `ignoresBranchDeletion`, `ignoresTagPush`, `parsesPullRequestCreatedAndUpdated`, `detectsForkPullRequest` (source repo full name differs from destination), `detectsDraft`, `unsupportedEventKeyYieldsEmptyList`, `malformedJsonThrowsInvalidPayload`, `missingRepositoryThrowsInvalidPayload`.
- [ ] **Step 2: Run** `mvn -q test -Dtest=EventParserTest` → FAIL.
- [ ] **Step 3: Implement** types and `EventParser` using Jackson tree traversal.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat: add webhook event model and parser`

### Task 3: Repository URL normalization

**Files:**
- Create: `routing/RepoKey.java`
- Test: `routing/RepoKeyTest.java`

**Interfaces:**
- Produces: `record RepoKey(String workspace, String slug)`; `static Optional<RepoKey> fromUrl(String url)`; `static RepoKey fromFullName(String fullName)`; keys are lowercase, stripped of `.git`, trailing slash, and userinfo.

- [ ] **Step 1: Write failing tests**: `https://user@bitbucket.org/Ws/Repo.git/`, `git@bitbucket.org:ws/repo.git`, `ssh://git@bitbucket.org/ws/repo`, `https://bitbucket.org/ws/repo` all equal `RepoKey("ws","repo")`; `https://github.com/ws/repo` → empty; garbage → empty.
- [ ] **Step 2: Run** `-Dtest=RepoKeyTest` → FAIL.
- [ ] **Step 3: Implement** with host check `bitbucket.org` and regex path extraction.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat: add repo URL normalization`

### Task 4: Webhook signature verification

**Files:**
- Create: `webhook/SignatureVerifier.java`
- Test: `webhook/SignatureVerifierTest.java`

**Interfaces:**
- Produces: `SignatureVerifier.verify(byte[] body, String header, String secret) -> boolean` — constant-time (`MessageDigest.isEqual`); false for null/blank header, missing `sha256=` prefix, wrong hex, or null/empty secret.

- [ ] **Step 1: Write failing tests**: valid signature (computed with `javax.crypto.Mac` in the test) → true; tampered body, wrong secret, missing header, `sha1=` prefix, non-hex → false.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat: add webhook signature verifier`

### Task 5: Authentication strategies and credentials

**Files:**
- Create: `auth/BitbucketAuth.java`, `auth/BasicAuth.java`, `auth/BearerAuth.java`, `auth/OAuthClientCredentialsAuth.java`, `auth/BitbucketOAuthCredentials.java`, `auth/AuthResolver.java`
- Test: `auth/AuthTest.java`

**Interfaces:**
- Produces:
  - `interface BitbucketAuth { void applyTo(java.net.http.HttpRequest.Builder b); default boolean refresh() { return false; } }`
  - `BasicAuth(String user, Secret password)` → `Authorization: Basic …`
  - `BearerAuth(Secret token)` → `Authorization: Bearer …`
  - `OAuthClientCredentialsAuth(String clientId, Secret clientSecret, URI tokenEndpoint, Clock clock)`: fetches via `grant_type=client_credentials` (default endpoint `https://bitbucket.org/site/oauth2/access_token`), caches the token until 60s before `expires_in`, `refresh()` forces a new token and returns true.
  - `BitbucketOAuthCredentials` — `StandardCredentials` with `clientId`, `Secret clientSecret`, `@Symbol("bitbucketOAuth")`.
  - `AuthResolver.resolve(String credentialsId) -> Optional<BitbucketAuth>` mapping `StandardUsernamePasswordCredentials` → Basic, `StringCredentials` → Bearer, `BitbucketOAuthCredentials` → OAuth.

- [ ] **Step 1: Write failing tests**: Basic/Bearer header values; OAuth fetches once and caches, re-fetches after expiry (WireMock + fixed `Clock`), `refresh()` re-fetches; resolver maps each credential type (`JenkinsRule` + `CredentialsProvider`).
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat: add auth strategies and credential types`

### Task 6: Bitbucket API client (commit status)

**Files:**
- Create: `api/BitbucketClient.java`, `api/CommitStatus.java`
- Test: `api/BitbucketClientTest.java` (WireMock)

**Interfaces:**
- Consumes: `BitbucketAuth` (Task 5), `RepoKey` (Task 3).
- Produces:
  - `record CommitStatus(String key, String name, String state, String url, String description)`
  - `BitbucketClient(URI baseUrl, BitbucketAuth auth, Sleeper sleeper)` where `interface Sleeper { void sleep(Duration d) throws InterruptedException; }`; `void postCommitStatus(RepoKey repo, String commitHash, CommitStatus status)` — never throws; logs WARNING without secrets.
  - Retry: 429/5xx and `IOException` retry up to 3 times with exponential backoff (1s, 2s, 4s via `Sleeper`); 401 → call `auth.refresh()` and retry once if it returned true; 401 otherwise and 403 → warn, no retry.

- [ ] **Step 1: Write failing tests**: request path/body/Authorization match; 500,500,200 → 3 requests; persistent 429 → 4 total requests; 403 → 1 request; 401 with refreshable auth → 2 requests; connection reset → no exception.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** with `java.net.http.HttpClient`. **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat: add Bitbucket API client with retry`

### Task 7: Global configuration

**Files:**
- Create: `config/BitbucketGlobalConfig.java`, `src/main/resources/io/jenkins/plugins/bitbucketcloud/config/BitbucketGlobalConfig/config.jelly`
- Test: `config/BitbucketGlobalConfigTest.java`

**Interfaces:**
- Produces: `@Extension GlobalConfiguration` with `getWebhookSecretId()`, `getDefaultCredentialsId()`, `isAllowUnsignedWebhooks()` (default false), `getApiBaseUrl()` (default `https://api.bitbucket.org`); `Optional<String> webhookSecret()` resolving the Secret text credential; `static BitbucketGlobalConfig get()`.

- [ ] **Step 1: Write failing test**: configuration round-trips through `j.configRoundtrip()`; `webhookSecret()` resolves a `StringCredentials`; defaults as stated.
- [ ] **Step 2–4:** run FAIL, implement, run PASS.
- [ ] **Step 5: Commit** `feat: add global configuration`

### Task 8: Webhook endpoint

**Files:**
- Create: `webhook/BitbucketWebhookAction.java`, `webhook/EventDispatcher.java`
- Test: `webhook/BitbucketWebhookActionTest.java`

**Interfaces:**
- Consumes: `SignatureVerifier`, `EventParser`, `BitbucketGlobalConfig`.
- Produces: `@Extension UnprotectedRootAction` with `getUrlName() == "bitbucket-webhook"`, handling POST only. `interface EventDispatcher extends ExtensionPoint { void dispatch(BitbucketEvent e); }` — implemented in Tasks 10 and 11; dispatch runs asynchronously on `Computer.threadPoolForRemoting`, each event/dispatcher isolated.
- Response codes: 401 bad/missing signature or no secret (unless allow-unsigned); 400 `InvalidPayloadException`; 200 for ignored/unsupported events; 202 when ≥1 event dispatched.

- [ ] **Step 1: Write failing tests** with `JenkinsRule` and a test `EventDispatcher`: valid signed push → 202 and dispatcher called once; bad signature → 401, dispatcher not called; no secret configured → 401; allow-unsigned → 202; malformed JSON → 400; unsupported `X-Event-Key` → 200; GET → 405; a dispatcher exception for one event does not affect others.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat: add webhook endpoint`

### Task 9: Build action, env vars, and trigger

**Files:**
- Create: `trigger/BitbucketBuildAction.java`, `trigger/BranchFilter.java`, `trigger/BitbucketTrigger.java`, `.../trigger/BitbucketTrigger/config.jelly`
- Test: `trigger/BranchFilterTest.java`, `trigger/BitbucketTriggerTest.java`

**Interfaces:**
- Consumes: `BitbucketEvent` (Task 2).
- Produces:
  - `BitbucketBuildAction implements EnvironmentContributingAction` with `repoFullName, commitHash, sourceBranch, targetBranch, prId, actor`; constructor `BitbucketBuildAction(BitbucketEvent)`; exports the six env vars from Global Constraints (unset ones omitted).
  - `BranchFilter(String includes, String excludes)`; `boolean matches(String branch)` — space/comma-separated Ant patterns, blank includes = all, excludes win.
  - `BitbucketTrigger extends Trigger<Job<?,?>>`, `@DataBoundConstructor`, `@DataBoundSetter` fields `pushEvents`, `pullRequestCreated`, `pullRequestUpdated`, `sourceBranchIncludes`, `sourceBranchExcludes`, `targetBranchIncludes`, `targetBranchExcludes`, `ignoreDraft` (default true), `allowForkPullRequests` (default false), `skipDuplicateCommit` (default true), `credentialsId`; `@Symbol("bitbucketCloud")`; `boolean accepts(BitbucketEvent e)`; `boolean fire(BitbucketEvent e)` queues a build with `CauseAction` + `BitbucketBuildAction`, returns whether queued. Duplicate = commit hash among the last 50 fired by this trigger (in-memory).

- [ ] **Step 1: Write failing tests**: `BranchFilterTest` (blank, include, exclude-wins, comma/space lists, `feature/**`); `BitbucketTriggerTest`: event/flag combinations accepted or rejected, draft ignored by default, fork PR rejected by default, duplicate commit skipped once and allowed when dedupe disabled, env vars present in a Freestyle build's `EnvVars`, Pipeline `triggers { bitbucketCloud(pushEvents: true) }` resolves via `@Symbol`.
- [ ] **Step 2: Run** → FAIL. **Step 3: Implement.** **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat: add trigger, filters, and env var action`

### Task 10: Job matching (Freestyle and Pipeline)

**Files:**
- Create: `routing/JobMatcher.java`, `routing/TriggerEventDispatcher.java`
- Test: `routing/JobMatcherTest.java`

**Interfaces:**
- Consumes: `RepoKey`, `BitbucketTrigger`, `EventDispatcher`.
- Produces: `JobMatcher.findTriggers(RepoKey repo) -> List<BitbucketTrigger>` scanning all `ParameterizedJob`s having a `BitbucketTrigger` whose `SCMTriggerItem.getSCMs()` include a `GitSCM` remote URL normalizing to `repo`; runs under `ACL.as2(ACL.SYSTEM2)`. `TriggerEventDispatcher` (`@Extension`, implements `EventDispatcher`) calls `accepts` then `fire` per match, catching and logging exceptions per job.

- [ ] **Step 1: Write failing tests**: Freestyle job with Git SCM `https://bitbucket.org/ws/repo.git` + trigger is matched by `ws/Repo`; job for another repo not matched; job without trigger not matched; Pipeline-from-SCM job matched; one throwing job does not stop others.
- [ ] **Step 2–4:** FAIL, implement, PASS.
- [ ] **Step 5: Commit** `feat: add job matching and trigger dispatch`

### Task 11: Multibranch support

**Files:**
- Create: `routing/BitbucketHeadEvent.java`, `routing/MultibranchEventDispatcher.java`
- Test: `routing/MultibranchEventDispatcherTest.java`

**Interfaces:**
- Consumes: `EventDispatcher`, `RepoKey`.
- Produces: `BitbucketHeadEvent extends SCMHeadEvent<BitbucketEvent>` whose `isMatch(SCMNavigator/SCMSource)` compares `RepoKey` of the `GitSCMSource` remote, and `heads(SCMSource)` returns the pushed/PR branch head with its commit hash; `@Extension MultibranchEventDispatcher implements EventDispatcher` calling `SCMHeadEvent.fireNow(...)`.

- [ ] **Step 1: Write failing test**: with a Multibranch project on a `GitSCMSource` whose remote normalizes to the event repo (use a test seam for the URL→`RepoKey` resolver pointing at a local repo), a push event triggers branch indexing/build; an unrelated repo does nothing.
- [ ] **Step 2–4:** FAIL, implement, PASS.
- [ ] **Step 5: Commit** `feat: support multibranch via scm-api head events`

### Task 12: Status reporting

**Files:**
- Create: `status/StatusReporter.java`
- Test: `status/StatusReporterTest.java`

**Interfaces:**
- Consumes: `BitbucketBuildAction`, `BitbucketClient`, `AuthResolver`, `BitbucketGlobalConfig`.
- Produces: `@Extension RunListener<Run<?,?>>`: `onStarted` posts `INPROGRESS`; `onCompleted` posts `SUCCESSFUL` (SUCCESS/UNSTABLE), `STOPPED` (ABORTED), `FAILED` otherwise. Credentials: trigger `credentialsId` → global default; none → skip silently. Repo/commit from `BitbucketBuildAction`; when absent (Multibranch builds), derived from `GitSCM` build data and the remote URL via `RepoKey`. Never changes the build result.

- [ ] **Step 1: Write failing tests** (WireMock): start → INPROGRESS body with key/name/url; success/failure/abort mapped correctly; no credentials → no request; API 500 → build still SUCCESS; Multibranch build without action still reports.
- [ ] **Step 2–4:** FAIL, implement, PASS.
- [ ] **Step 5: Commit** `feat: report build status to Bitbucket`

### Task 13: End-to-end tests and docs

**Files:**
- Create: `src/test/java/io/jenkins/plugins/bitbucketcloud/EndToEndTest.java`
- Modify: `README.md`

- [ ] **Step 1: Write tests** POSTing signed fixture payloads to `/bitbucket-webhook/` for Freestyle, Pipeline, and Multibranch jobs; assert a build is queued, env vars set, and WireMock receives INPROGRESS then SUCCESSFUL; push then PR-update on the same commit → one build; workspace-hook event for an unknown repo → no builds.
- [ ] **Step 2: Run** `mvn -q verify` → fix wiring gaps until the new tests fail only for real reasons, then make them pass.
- [ ] **Step 3: README**: workspace webhook setup (URL, secret), credential types, required Bitbucket scopes (repository read, pull request read, repository write for statuses), Pipeline snippet, env var table.
- [ ] **Step 4: Run** `mvn -q verify` → PASS.
- [ ] **Step 5: Commit** `test: add end-to-end coverage and README`
