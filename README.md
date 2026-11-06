# Bitbucket Cloud Plugin

Jenkins plugin that triggers builds from Bitbucket Cloud webhooks and reports commit build statuses back to Bitbucket.

## Build

    mvn verify

## Setup

### Webhook

Create a **workspace webhook** in Bitbucket Cloud (Workspace settings → Webhooks) with:

- **URL:** `https://<jenkins>/bitbucket-webhook/`
- **Secret:** a random string. Store the same value in Jenkins as a *Secret text* credential and select it as
  *Webhook secret* under Manage Jenkins → System → Bitbucket Cloud. Requests are verified by their
  `X-Hub-Signature` HMAC-SHA256 header; unsigned requests are rejected unless *Allow unsigned webhooks* is enabled.
- **Triggers:** Repository push, Pull request created, Pull request updated.

Events for repositories that no job or multibranch source points at are ignored.

### Credentials

Used to post commit build statuses. Set a default under Manage Jenkins → System → Bitbucket Cloud, or per job on the
trigger. Supported types:

- Username with password (Bitbucket username + app password / API token)
- Secret text (repository / workspace access token, sent as a Bearer token)
- Bitbucket OAuth consumer (client credentials)

Required Bitbucket scopes: **repository read**, **pull request read**, **repository write** (commit statuses).

### Jobs

Freestyle and Pipeline jobs: enable *Build when a change is pushed to Bitbucket Cloud* and use a Git SCM whose
remote is the Bitbucket repository. Multibranch projects need no trigger; push and pull-request events refresh
the matching branch source.

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
