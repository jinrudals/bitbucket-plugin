# Task 5 report
Built BitbucketAuth, BasicAuth, BearerAuth, OAuthClientCredentialsAuth (cache until expires_in-60s, refresh() forces fetch), BitbucketOAuthCredentials (+@Extension descriptor, @Symbol bitbucketOAuth, credentials.jelly), AuthResolver (lookupCredentialsInItemGroup with ACL.SYSTEM2).
TDD: RED `mvn -q test -Dtest=AuthTest` -> compilation error (symbols missing). GREEN -> 3 tests, 0 failures; full `mvn -q test` passes.
WireMock 3.9.1 standalone works with JenkinsRule/@WireMockTest; no pom change.
Concerns: resolver only looks up global (Jenkins-root) credentials; no item-scoped lookup. Token fetch failures throw IllegalStateException (no secrets in messages).
