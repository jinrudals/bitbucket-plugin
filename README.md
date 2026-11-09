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
3. Optionally narrow what builds: **Allowed events** (default `repo:push`; e.g. `pullrequest:created`) and
   **Include / Exclude branches** (space-separated wildcards; pull request events are matched on the source branch).

Deliveries are verified with the `X-Hub-Signature` HMAC-SHA256 header. Unknown jobs return 404, bad signatures 403, and
deliveries that fail the filters return 200 with a `skipped: ...` body without scheduling a build.

## Build

    mvn verify
