# TODO: Per-job Bitbucket webhook trigger (MVP)

## Goal

Let a Bitbucket Cloud webhook target exactly one Jenkins job. The job is
identified by the URL path, modeled on the Jenkins GitLab Plugin
(`/project/<folder>/<job>`). Users must not have to assemble the URL or the
secret by hand: both are shown/generated in the job configuration page.

## Webhook URL

```
<JENKINS_ROOT_URL>/bitbucket/<folder>/<job>
```

- Nested folders are supported: `/bitbucket/a/b/job`.
- The prefix `/bitbucket/` was chosen to avoid clashing with other plugins
  that use `/bitbucket-hook/`, `/bitbucket-scmsource-hook/` and
  `/bitbucket-server-webhook/`. Re-verify there is no collision when implementing.

## Work items

### 1. Root action and routing
- [ ] `BitbucketWebHook implements UnprotectedRootAction`, `urlName = "bitbucket"`.
- [ ] `getDynamic(String first, StaplerRequest2, StaplerResponse2)` resolves the job:
  - Run as `ACL.SYSTEM`.
  - `Jenkins.getItemByFullName(first)`, then walk the remaining path segments
    while the item is an `ItemGroup` that is not a `Job`.
  - Accept `Job` (multibranch / `SCMSourceOwner` is out of scope for the MVP).
- [ ] Respond `404` when no job matches, `405` for non-POST methods.
- [ ] `CrumbExclusion` for paths starting with `/bitbucket/`.

### 2. Trigger
- [ ] `BitbucketPushTrigger extends Trigger<Job<?, ?>>` with a `@Symbol`
  and `@DataBoundConstructor`, enabled from "Build Triggers".
- [ ] Config page (`config.jelly`) shows the read-only webhook URL
  (`rootUrl + "bitbucket/" + <parent path> + "/" + Util.rawEncode(name)`).
- [ ] Secret is stored as `hudson.util.Secret` and auto-generated when empty.
- [ ] Jobs without the trigger enabled ignore the request (`200`, `skipped`).
- [ ] Trigger must be usable from Pipeline `properties([...])` and JCasC/Job DSL.

### 3. Signature verification
- [ ] Validate `X-Hub-Signature` (`sha256=<hex>`, HMAC-SHA256 of the raw body)
  using the trigger secret.
- [ ] Constant-time comparison; read the raw body once.
- [ ] Missing/invalid signature returns `403` when a secret is configured.

### 4. Event and branch filtering
- [ ] Read `X-Event-Key` (e.g. `repo:push`, `pullrequest:created`,
  `pullrequest:updated`, `pullrequest:fulfilled`).
- [ ] Trigger options: allowed event keys (default: `repo:push` only).
- [ ] Branch/tag include and exclude patterns (wildcard), applied to the
  pushed ref names or the pull request source/destination branch.
- [ ] A request that passes verification but fails filters returns `200`
  with a `skipped` body and does not schedule a build.

### 5. Scheduling
- [ ] On match, schedule a build with cause `BitbucketWebHookCause`
  (event key, repository, branch, actor) shown on the build page.
- [ ] Respect the job's quiet period; do not require job read permission
  for the webhook caller.

### 6. Tests
- [ ] Path resolution: top-level job, nested folders, unknown path, non-job item.
- [ ] Signature: valid, invalid, missing, no secret configured.
- [ ] Filters: event key, branch include/exclude, skipped response.
- [ ] Trigger disabled job is not built.
- [ ] Config round trip (`JenkinsRule.configRoundtrip`).

### 7. Docs
- [ ] README section: how to configure the webhook in Bitbucket Cloud
  (URL, secret, selected events).
- [ ] Add a design note under `docs/harness/` once the code lands.

## Out of scope for the MVP (follow-ups)

- [ ] Payload JSONPath conditions (Generic Webhook Trigger style).
- [ ] Extract payload values into build parameters / environment variables.
- [ ] Repository and author filters.
- [ ] Multibranch (`SCMSourceOwner`) targets.
- [ ] Optional pull-request build status reporting back to Bitbucket.
