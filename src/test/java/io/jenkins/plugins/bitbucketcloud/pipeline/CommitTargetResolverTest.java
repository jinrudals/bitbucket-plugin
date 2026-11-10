package io.jenkins.plugins.bitbucketcloud.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import io.jenkins.plugins.bitbucketcloud.api.CommitTarget;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CommitTargetResolverTest {

    private static final Map<String, String> NONE = Map.of();

    private static final Map<String, String> PUSH = Map.of(
            "BITBUCKET_WORKSPACE", "acme",
            "BITBUCKET_REPOSITORY_NAME", "shop",
            "BITBUCKET_COMMIT_AFTER", "aaa111");

    private static CommitTargetResolver.Resolved resolve(
            String workspace, String repo, String commit, Map<String, String> action, Map<String, String> env) {
        return CommitTargetResolver.resolve(workspace, repo, commit, action, env);
    }

    @Test
    void explicitArgumentsNeedNoSource() {
        var r = resolve("ws", "repo", "abc", NONE, NONE);

        assertEquals(new CommitTarget("ws", "repo", "abc"), r.target());
        assertEquals("arguments", r.source());
    }

    @Test
    void actionWinsOverEnvironment() {
        Map<String, String> env = Map.of(
                "BITBUCKET_WORKSPACE", "other",
                "BITBUCKET_REPOSITORY_NAME", "other-repo",
                "BITBUCKET_COMMIT_AFTER", "zzz999");

        var r = resolve(null, null, null, PUSH, env);

        assertEquals(new CommitTarget("acme", "shop", "aaa111"), r.target());
        assertEquals("BitbucketEnvAction", r.source());
    }

    @Test
    void environmentIsUsedWhenThereIsNoAction() {
        var r = resolve(null, null, null, NONE, PUSH);

        assertEquals(new CommitTarget("acme", "shop", "aaa111"), r.target());
        assertEquals("environment", r.source());
    }

    @Test
    void pullRequestEventUsesSourceCommit() {
        Map<String, String> pr = Map.of(
                "BITBUCKET_WORKSPACE", "acme",
                "BITBUCKET_REPOSITORY_NAME", "shop",
                "BITBUCKET_PR_SOURCE_COMMIT", "bbb222");

        assertEquals("bbb222", resolve(null, null, null, pr, NONE).target().commit());
    }

    @Test
    void gitScmIsTheLastFallback() {
        Map<String, String> env = Map.of(
                "GIT_URL", "https://user@bitbucket.org/acme/shop.git",
                "GIT_COMMIT", "ccc333");

        var r = resolve(null, null, null, NONE, env);

        assertEquals(new CommitTarget("acme", "shop", "ccc333"), r.target());
        assertEquals("Git SCM", r.source());
    }

    @Test
    void gitScmParsesSshUrls() {
        Map<String, String> env = Map.of(
                "GIT_URL", "git@bitbucket.org:acme/shop.git",
                "GIT_COMMIT", "ccc333");

        assertEquals("shop", resolve(null, null, null, NONE, env).target().repo());
    }

    @Test
    void nonBitbucketGitUrlIsIgnored() {
        Map<String, String> env = Map.of("GIT_URL", "https://github.com/acme/shop.git", "GIT_COMMIT", "ccc333");

        assertThrows(IllegalArgumentException.class, () -> resolve(null, null, null, NONE, env));
    }

    @Test
    void sourcesAreNeverMixed() {
        // The action has workspace and repo but no commit; the environment has only a commit.
        Map<String, String> action = Map.of("BITBUCKET_WORKSPACE", "acme", "BITBUCKET_REPOSITORY_NAME", "shop");
        Map<String, String> env = Map.of("BITBUCKET_COMMIT_AFTER", "zzz999");

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> resolve(null, null, null, action, env));
        assertTrue(e.getMessage().contains("workspace"));
    }

    @Test
    void argumentsOverrideAndSourceSuppliesTheRest() {
        var r = resolve(null, null, "explicit", PUSH, NONE);

        assertEquals(new CommitTarget("acme", "shop", "explicit"), r.target());
    }

    @Test
    void sourceNeedsOnlyTheFieldsTheArgumentsLeaveOpen() {
        Map<String, String> action = Map.of("BITBUCKET_WORKSPACE", "acme", "BITBUCKET_REPOSITORY_NAME", "shop");

        var r = resolve(null, null, "explicit", action, NONE);

        assertEquals(new CommitTarget("acme", "shop", "explicit"), r.target());
    }

    @Test
    void failureNamesTheUnresolvedFields() {
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> resolve(null, null, null, NONE, NONE));

        assertTrue(e.getMessage().contains("workspace"));
        assertTrue(e.getMessage().contains("repo"));
        assertTrue(e.getMessage().contains("commit"));
    }

    @Test
    void blankValuesCountAsMissing() {
        Map<String, String> env = Map.of(
                "BITBUCKET_WORKSPACE", "acme",
                "BITBUCKET_REPOSITORY_NAME", "shop",
                "BITBUCKET_COMMIT_AFTER", "");

        assertThrows(IllegalArgumentException.class, () -> resolve(null, null, null, NONE, env));
    }
}
