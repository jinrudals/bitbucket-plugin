package io.jenkins.plugins.bitbucketcloud.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jenkins.plugins.bitbucketcloud.event.PullRequestEvent;
import io.jenkins.plugins.bitbucketcloud.event.PushEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import jenkins.branch.BranchSource;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.jupiter.api.io.TempDir;
import jenkins.plugins.git.GitSCMSource;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMRevision;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class MultibranchEventDispatcherTest {

    private static final String REMOTE = "/local/repo-under-test";

    @BeforeEach
    void seam() {
        BitbucketHeadEvent.setResolver(
                url -> REMOTE.equals(url) ? Optional.of(new RepoKey("ws", "repo")) : Optional.empty());
    }

    @AfterEach
    void reset() {
        BitbucketHeadEvent.setResolver(null);
    }

    @Test
    void pushMatchesSourceAndYieldsBranchHead(JenkinsRule j) {
        var ev = new BitbucketHeadEvent(new PushEvent("ws/repo", "a", "feature/x", "abc123"), new RepoKey("ws", "repo"));
        var source = new GitSCMSource(REMOTE);
        assertTrue(ev.isMatch(source));
        var heads = ev.heads(source);
        assertEquals(1, heads.size());
        SCMHead head = heads.keySet().iterator().next();
        assertEquals("feature/x", head.getName());
        SCMRevision rev = heads.get(head);
        assertEquals("abc123", rev.toString());
    }

    @Test
    void unrelatedRepoDoesNotMatch(JenkinsRule j) {
        var ev = new BitbucketHeadEvent(new PushEvent("ws/other", "a", "main", "abc"), new RepoKey("ws", "other"));
        var source = new GitSCMSource(REMOTE);
        assertTrue(!ev.isMatch(source));
        assertTrue(ev.heads(source).isEmpty());
    }

    @Test
    void pullRequestUsesSourceBranch(JenkinsRule j) {
        var pr = new PullRequestEvent("ws/repo", "a", "created", "1", "topic", "main", "def456", false, false);
        var ev = new BitbucketHeadEvent(pr, new RepoKey("ws", "repo"));
        var heads = ev.heads(new GitSCMSource(REMOTE));
        assertEquals("topic", heads.keySet().iterator().next().getName());
    }

    @Test
    void pushEventIndexesAndBuildsNewBranch(JenkinsRule j, @TempDir Path dir) throws Exception {
        git(dir, "init", "-q", "-b", "main");
        Files.writeString(dir.resolve("Jenkinsfile"), "echo 'hi'\n");
        git(dir, "add", ".");
        git(dir, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "init");
        String remote = dir.toString();
        BitbucketHeadEvent.setResolver(
                url -> remote.equals(url) ? Optional.of(new RepoKey("ws", "repo")) : Optional.empty());

        WorkflowMultiBranchProject mbp = j.createProject(WorkflowMultiBranchProject.class, "mb");
        GitSCMSource source = new GitSCMSource(remote);
        source.setTraits(java.util.List.of(new jenkins.plugins.git.traits.BranchDiscoveryTrait()));
        mbp.getSourcesList().add(new BranchSource(source));
        mbp.scheduleBuild2(0).getFuture().get();
        assertNotNull(mbp.getItem("main"));
        assertNull(mbp.getItem("dev"));

        git(dir, "checkout", "-q", "-b", "dev");
        String sha = git(dir, "rev-parse", "HEAD").trim();

        new MultibranchEventDispatcher().dispatch(new PushEvent("other/repo", "a", "dev", sha));
        Thread.sleep(1500);
        assertNull(mbp.getItem("dev"));

        new MultibranchEventDispatcher().dispatch(new PushEvent("ws/repo", "a", "dev", sha));
        long end = System.currentTimeMillis() + 60_000;
        while (mbp.getItem("dev") == null && System.currentTimeMillis() < end) {
            Thread.sleep(200);
        }
        WorkflowJob dev = mbp.getItem("dev");
        assertNotNull(dev);
        while (dev.getLastBuild() == null && System.currentTimeMillis() < end) {
            Thread.sleep(200);
        }
        assertNotNull(dev.getLastBuild());
    }

    private static String git(Path dir, String... args) throws Exception {
        var cmd = new java.util.ArrayList<String>();
        cmd.add("git");
        cmd.addAll(java.util.List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), out);
        return out;
    }

    @Test
    void dispatcherToleratesMalformedAndUnmatchedEvents(JenkinsRule j) {
        var d = new MultibranchEventDispatcher();
        d.dispatch(new PushEvent("garbage", "a", "main", "abc"));
        d.dispatch(new PushEvent("ws/other", "a", "main", "abc"));
    }
}
