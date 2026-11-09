# Bitbucket Cloud Plugin

Jenkins plugin that triggers builds from Bitbucket Cloud webhooks and reports commit build statuses back to Bitbucket.

## Build

    mvn verify

## Setup

### Webhook

Create one webhook per Jenkins job, at repository level (Repository settings → Webhooks) or workspace level, with:

- **URL:** `https://<jenkins>/bitbucket-webhook/job/<job full name>` (e.g. `…/bitbucket-webhook/job/my-job`, or
  `…/job/my-folder/my-job` for a job in a folder). The event is delivered only to that job's trigger; there is no
  global routing, so `/bitbucket-webhook/` alone answers 404.
- **Secret:** a random string. Store the same value in Jenkins as a *Secret text* credential and select it as
  *Webhook secret* under Manage Jenkins → System → Bitbucket Cloud. Requests are verified by their
  `X-Hub-Signature` HMAC-SHA256 header; unsigned requests are rejected unless *Allow unsigned webhooks* is enabled.
- **Triggers:** Repository push, Pull request created, Pull request updated.

The job must have the *Build when a change is pushed to Bitbucket Cloud* trigger. Its repository (the explicit
*Repository* field, or the Git SCM remote) and branch filters still decide whether an event builds.

### Credentials

Used to post commit build statuses. Set a default under Manage Jenkins → System → Bitbucket Cloud, or per job on the
trigger. Supported types:

- Username with password (Bitbucket username + app password / API token)
- Secret text (repository / workspace access token, sent as a Bearer token)
- Bitbucket OAuth consumer (client credentials)

Credentials are looked up at Jenkins global scope (system and global credentials), not in folder scope. The
credential selected as the *Webhook secret* is never used as an API credential.

Required Bitbucket scopes: **repository read**, **pull request read**, **repository write** (commit statuses).

### Jobs

Freestyle and Pipeline jobs: enable *Build when a change is pushed to Bitbucket Cloud* and either use a Git SCM
whose remote is the Bitbucket repository or set the trigger's `repository` (`workspace/repo`). Multibranch
projects are not triggered by webhooks.

```groovy
pipeline {
  agent any
  triggers { bitbucketCloud(pushEvents: true, pullRequestCreated: true, pullRequestUpdated: true) }
  stages {
    stage('Build') {
      steps { echo "Building ${env.bitbucketSourceBranch} @ ${env.bitbucketCommitHash}" }
    }
  }
}
```

### Environment variables

Set on builds started by a webhook event (Freestyle and Pipeline jobs).

| Variable | Value |
| --- | --- |
| `bitbucketSourceBranch` | Pushed branch, or the pull request source branch |
| `bitbucketTargetBranch` | Pushed branch, or the pull request destination branch |
| `bitbucketPullRequestId` | Pull request id (pull request events only) |
| `bitbucketRepoFullName` | `workspace/repo` |
| `bitbucketCommitHash` | Commit being built |
| `bitbucketActor` | User who triggered the event |
