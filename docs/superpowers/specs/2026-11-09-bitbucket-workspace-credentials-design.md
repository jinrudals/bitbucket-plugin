# Bitbucket Cloud Workspace Credential Management

## Goal

Replace the plugin's current job-trigger and build-status integration with a
focused Jenkins administration experience for Bitbucket Cloud workspace
connections. Jenkins administrators should be able to manage one connection per
workspace from **Manage Jenkins → System**, using credentials already stored in
Jenkins.

## Scope

### In scope

- Manage multiple Bitbucket Cloud workspace connections in Jenkins System
  configuration.
- Store each workspace slug and a Jenkins credential ID reference.
- Select global/system credentials supporting:
  - `StandardUsernamePasswordCredentials`
  - `StringCredentials` (secret-text credential for HTTP authorization)
  - `BitbucketOAuthCredentials` (OAuth consumer)
- Add, edit, and remove workspace connections.
- Test an individual connection with a read-only Bitbucket Cloud API request.
- Validate configuration, enforce administrator-only operations, and report
  errors without exposing credentials or response bodies.

### Out of scope

- Repository-level configuration or management.
- Bitbucket webhook endpoints, signature configuration, job triggers, event
  routing, build environment variables, commit status reporting, and Pipeline
  steps.
- Bitbucket Data Center or Bitbucket Server.
- Storing raw passwords, tokens, or OAuth client secrets in plugin
  configuration.
- Automatic migration of the removed webhook/default-credential settings into
  workspace connections. Administrators re-add connections after upgrading.

## Approaches considered

1. **Workspace-to-credential mapping in Jenkins System configuration
   (recommended).** The plugin stores workspace slugs and credential IDs while
   Jenkins Credentials remains the source of truth for secrets. This follows
   Jenkins conventions, supports existing credential types, and allows
   credentials to be rotated or reused independently of the plugin settings.
2. **Bitbucket-specific credential objects containing workspace identity.**
   This gives the credential type stronger domain semantics but duplicates
   workspace management in the Credentials UI and requires maintaining
   Bitbucket-specific credential variants.
3. **Secret fields directly in the plugin's System form.** This is simple to
   display but bypasses Jenkins Credentials, complicates secret reuse and
   rotation, and increases the risk of secret exposure in configuration.

## Design

### Configuration model

The global plugin configuration contains a list of workspace connection
records. Each record contains only:

- The Bitbucket Cloud workspace slug.
- The selected Jenkins credential ID.

Workspace slugs are trimmed and validated as a single path segment. A
workspace may appear only once, compared case-insensitively; the submitted
spelling is retained for display and API calls. Empty or malformed entries
are rejected rather than silently ignored. Credentials are selected from
Jenkins' global/system credential stores and filtered to the supported types
above. Folder-scoped credentials are not available in global System settings.

No separate display name, repository list, API base URL, raw secret, or
workspace access token is stored in this configuration.

### Jenkins administration UI

The Bitbucket Cloud section under **Manage Jenkins → System** provides a
repeatable workspace connection list. An administrator can add, edit, and
remove entries, selecting a workspace slug and a credential for each one.

Each entry provides a **Test connection** action that validates the values
currently entered in that entry, including unsaved edits. The action is
available only to users with `Jenkins.ADMINISTER` permission and is submitted
as a POST request.

### Connection test

The test uses the fixed Bitbucket Cloud HTTPS API host and sends a read-only
request:

```text
GET /2.0/workspaces/{workspace}
```

The plugin resolves the selected credential through Jenkins
`CredentialsProvider` under `ACL.SYSTEM2` and applies its supported
authentication strategy. A 2xx response indicates that the credential can
read the selected workspace. The request has a bounded connection/request
timeout and does not retry.

Missing or invalid inputs are rejected before making a request. Network errors
and non-2xx responses produce explicit, sanitized feedback. The UI and logs
must not include credential material, access tokens, OAuth responses, or
Bitbucket response bodies. HTTP status and a safe error category may be
included in the message.

The test does not modify Bitbucket resources. It does not configure arbitrary
API hosts or perform repository operations.

### Error handling and security

- Global configuration and credential selection follow Jenkins administrator
  permission rules.
- Credential lookup is constrained to Jenkins global/system scope and the
  Bitbucket Cloud URI requirement.
- The plugin persists credential IDs only; secret values remain managed and
  encrypted by Jenkins Credentials.
- Invalid workspace slugs, duplicate workspace entries, missing credentials,
  inaccessible credentials, timeouts, authentication/authorization failures,
  missing workspaces, and unexpected API responses are surfaced explicitly.
- Failures never appear as successful connection tests.
- Logs contain safe diagnostic categories and never secret values or response
  bodies.

### Removal and documentation

Remove the old webhook, trigger, event-routing, build-status, and Pipeline-step
features and their dedicated tests and documentation. Remove dependencies and
settings that are no longer needed, while retaining the Jenkins plugin
scaffold and reusable credential/authentication code. Rewrite the README
around workspace connection setup, supported credential types, permissions,
and connection testing.

Git history and unrelated repository metadata remain unchanged.

## Testing

- JenkinsRule tests cover default configuration, persistence round-trip,
  multiple workspace entries, duplicate/invalid workspace validation,
  credential filtering, and administrator-only actions.
- WireMock tests cover workspace API success, authentication with all
  supported credential types, non-2xx responses, and network/timeout failures.
- Tests verify that secret material and API response bodies are not included
  in returned messages or logs.
- Run `mvn verify` to validate the plugin after obsolete integration tests are
  removed and workspace-management coverage is added.
