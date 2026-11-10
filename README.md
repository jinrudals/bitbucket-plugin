# Bitbucket Cloud Plugin

Jenkins plugin for managing Bitbucket Cloud workspace credentials and testing read-only access.

## Configure

Open **Manage Jenkins → System → Bitbucket Cloud** and add one connection for each workspace:

1. Enter the Bitbucket Cloud workspace slug.
2. Select a credential from the Jenkins Credentials store.
3. Use **Test connection** to verify that the credential can read the workspace.

Supported credential type:

- **Bitbucket Authentication** — Bitbucket username and API token/app password.

Credentials are selected from Jenkins system/global credentials. Grant each credential sufficient Bitbucket Cloud
permissions to read its workspace. The connection test sends a read-only request to the fixed Bitbucket Cloud API
endpoint and requires Jenkins administrator permission. It does not modify workspace data.

## Per-job webhook trigger

Each job receives Bitbucket Cloud webhooks on its own URL, so a delivery can only ever build the job it addresses:

    <JENKINS_ROOT_URL>/bitbucket/<folder>/<job>

1. In the job configuration, enable **Build when a Bitbucket Cloud webhook is received**. The webhook URL and a
   generated secret are shown there.
2. In Bitbucket Cloud, add a webhook with that URL and secret (Repository settings → Webhooks) and select the events.
3. Add one or more **rules**. A delivery builds the job when any rule matches. Each rule has **Events**
   (default `repo:push`; e.g. `pullrequest:created`) and **Include / Exclude branches** (space-separated wildcards;
   pull request events are matched on the source branch). Without rules, pushes to any branch build the job.

The secret can also come from a Jenkins **Secret text** credential (`secretCredentialsId`), which replaces the generated
secret and is recommended when the trigger is declared in a Jenkinsfile, where a generated secret would change on every
run. A credential that cannot be found makes every delivery fail with 403.

Deliveries are verified with the `X-Hub-Signature` HMAC-SHA256 header. Unknown jobs return 404, bad signatures 403, and
deliveries that match no rule return 200 with a `skipped: ...` body without scheduling a build.

Pipeline:

    properties([pipelineTriggers([bitbucket(rules: [rule(events: 'repo:push', includeBranches: 'main')])])])

Job DSL:

    triggers {
      bitbucket {
        rules {
          rule { events('repo:push'); includeBranches('main') }
          rule { events('pullrequest:created'); excludeBranches('wip/*') }
        }
      }
    }

### Environment variables

Builds triggered by a webhook get the variables below. A variable whose value is missing from the payload is set to an
empty string. Event-specific variables are only defined for their event type (for example `BITBUCKET_PR_ID` does not
exist in a `repo:push` build). Other events, such as `repo:fork`, only get the common variables.

Each variable is read from the Bitbucket Cloud webhook payload, except `BITBUCKET_REPO_SSH_URL` and, when the payload has
no workspace slug, `BITBUCKET_WORKSPACE`, which are derived from `repository.full_name`.

**Common (all events)**

| Variable | Description | Example |
|---|---|---|
| `BITBUCKET_EVENT_KEY` | Value of the `X-Event-Key` header: the event that triggered the build. | `repo:push` |
| `BITBUCKET_REPOSITORY` | Full repository name, `<workspace>/<repo>`. | `acme/shop` |
| `BITBUCKET_REPOSITORY_NAME` | Repository name without the workspace. | `shop` |
| `BITBUCKET_WORKSPACE` | Workspace slug. | `acme` |
| `BITBUCKET_REPOSITORY_URL` | Web (HTML) URL of the repository. | `https://bitbucket.org/acme/shop` |
| `BITBUCKET_REPO_SSH_URL` | SSH clone URL, built as `git@ssh.bitbucket.org:<workspace>/<repo>.git` (the payload carries no clone links). | `git@ssh.bitbucket.org:acme/shop.git` |
| `BITBUCKET_ACTOR` | Display name of the user who caused the event. | `Jane Doe` |
| `BITBUCKET_ACTOR_ACCOUNT_ID` | Atlassian account ID of that user. | `712020:495d...` |
| `BITBUCKET_REF` | The ref that satisfied the matching rule: the pushed branch or tag name for `repo:push`, or the pull request source branch for `pullrequest:*`. | `main` |

**`repo:push` only**

The values come from the pushed change that matches `BITBUCKET_REF`. A push can contain several changes; the build is
started for the first one that a rule accepts.

| Variable | Description |
|---|---|
| `BITBUCKET_REF_TYPE` | Type of the pushed ref: `branch` or `tag`. |
| `BITBUCKET_COMMIT_BEFORE` | Commit hash the ref pointed to before the push. Empty when the ref is new. |
| `BITBUCKET_COMMIT_AFTER` | Commit hash the ref points to after the push (the new head). |
| `BITBUCKET_COMMIT_MESSAGE` | Message of the new head commit. |

**`pullrequest:*` only**

| Variable | Description |
|---|---|
| `BITBUCKET_PR_ID` | Pull request number. |
| `BITBUCKET_PR_TITLE` | Pull request title. |
| `BITBUCKET_PR_STATE` | Pull request state, such as `OPEN`, `MERGED` or `DECLINED`. |
| `BITBUCKET_PR_URL` | Web (HTML) URL of the pull request. |
| `BITBUCKET_PR_SOURCE_BRANCH` | Branch the pull request is merged from. Same as `BITBUCKET_REF`. |
| `BITBUCKET_PR_SOURCE_COMMIT` | Head commit hash of the source branch. |
| `BITBUCKET_PR_TARGET_BRANCH` | Branch the pull request is merged into. |
| `BITBUCKET_PR_AUTHOR` | Display name of the pull request author, which can differ from `BITBUCKET_ACTOR`. |
| `BITBUCKET_PR_COMMENT` | Text of the comment for comment events (`pullrequest:comment_*`); empty for other pull request events. |

**Builds are not merged across different deliveries.** Bitbucket sends both `repo:push` and `pullrequest:updated` when
you push to a pull request branch. If both match a rule, they start two builds (one per event), each with its own
variables. Identical repeated deliveries are folded into one queued build. To get one build per update, select only one
of the two events in your rules.

A full example Jenkinsfile (trigger rules plus every environment variable) is in [docs/examples/Jenkinsfile](docs/examples/Jenkinsfile).

## Commit status from a pipeline

Two pipeline steps publish build statuses to Bitbucket Cloud. The workspace must be configured under
**Manage Jenkins → System → Bitbucket Cloud**; its credential is used for the request and its API token needs
permission to write commit statuses. Credentials are never passed as step arguments.

```groovy
// Publish one state.
updateBitbucketCommitStatus state: 'INPROGRESS'

// Wrap a block: INPROGRESS on start, then SUCCESSFUL, FAILED (error) or STOPPED (aborted).
bitbucketCommitStatus(name: 'build') {
    sh 'make'
}
```

| Argument | Applies to | Description | Default |
| --- | --- | --- | --- |
| `state` | `updateBitbucketCommitStatus` (required) | `INPROGRESS`, `SUCCESSFUL`, `FAILED` or `STOPPED`. | |
| `name` | both | Display name of the status. | `JOB_NAME` |
| `key` | both | Unique key of the status (max 40 characters). | `JOB_NAME`, shortened with a hash if longer than 40 characters |
| `description` | both | Short description. | none |
| `url` | both | Link shown with the status. | `BUILD_URL` |
| `workspace`, `repo`, `commit` | both | Commit to publish to. | resolved, see below |

Fields you do not pass are resolved from the first of these sources that provides all of them, so values from
different sources are never mixed. The build log names the source that was used.

1. The `BitbucketEnvAction` of the build (builds started by a webhook).
2. The `BITBUCKET_WORKSPACE`, `BITBUCKET_REPOSITORY_NAME` and `BITBUCKET_COMMIT_AFTER` (push) or
   `BITBUCKET_PR_SOURCE_COMMIT` (pull request) environment variables. Child builds get them from `setParentEnv`.
3. The Git SCM: `GIT_URL` (a `bitbucket.org` URL) and `GIT_COMMIT`.

A missing configuration, an unresolvable commit or an unknown `state` fails the step. A failing Bitbucket API call
(for example HTTP 403) only prints a warning and does not fail the build.

## Build

    mvn verify
