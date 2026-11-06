package io.jenkins.plugins.bitbucketcloud.routing;

import hudson.scm.SCM;
import io.jenkins.plugins.bitbucketcloud.event.BitbucketEvent;
import io.jenkins.plugins.bitbucketcloud.event.PullRequestEvent;
import io.jenkins.plugins.bitbucketcloud.event.PushEvent;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import jenkins.plugins.git.AbstractGitSCMSource;
import jenkins.plugins.git.GitSCMSource;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMHeadEvent;
import jenkins.scm.api.SCMNavigator;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import jenkins.plugins.git.GitBranchSCMHead;

/** Adapts a Bitbucket push / pull-request event to an scm-api head event for multibranch projects. */
public class BitbucketHeadEvent extends SCMHeadEvent<BitbucketEvent> {

    private static volatile Function<String, Optional<RepoKey>> resolver = RepoKey::fromUrl;

    private final RepoKey repo;

    public BitbucketHeadEvent(BitbucketEvent event, RepoKey repo) {
        super(typeOf(event), System.currentTimeMillis(), event, "bitbucket-cloud");
        this.repo = repo;
    }

    /** Test seam: overrides how a source remote URL is mapped to a repository. */
    static void setResolver(Function<String, Optional<RepoKey>> r) {
        resolver = r == null ? RepoKey::fromUrl : r;
    }

    private static Type typeOf(BitbucketEvent e) {
        // Merging or declining a PR does not delete the source branch, so never REMOVED.
        // Branch deletions are not surfaced by EventParser (push changes without a "new" ref are skipped).
        if (e instanceof PullRequestEvent pr && "created".equals(pr.type())) {
            return Type.CREATED;
        }
        return Type.UPDATED;
    }

    private boolean matches(SCMSource source) {
        return source instanceof GitSCMSource git
                && git.getRemote() != null
                && resolver.apply(git.getRemote()).filter(repo::equals).isPresent();
    }

    // Plain SCM polling is handled by TriggerEventDispatcher.
    @Override
    public boolean isMatch(SCM scm) {
        return false;
    }

    @Override
    public boolean isMatch(SCMNavigator navigator) {
        return false;
    }

    @Override
    public boolean isMatch(SCMSource source) {
        return matches(source);
    }

    @Override
    public String getSourceName() {
        return repo.slug();
    }

    @Override
    public Map<SCMHead, SCMRevision> heads(SCMSource source) {
        if (!matches(source)) {
            return Collections.emptyMap();
        }
        BitbucketEvent e = getPayload();
        String branch;
        if (e instanceof PushEvent push) {
            branch = push.branch();
        } else if (e instanceof PullRequestEvent pr) {
            // A fork's branch is not a branch of this repository.
            if (pr.fromFork()) {
                return Collections.emptyMap();
            }
            branch = pr.sourceBranch();
        } else {
            return Collections.emptyMap();
        }
        if (branch == null || branch.isBlank()) {
            return Collections.emptyMap();
        }
        SCMHead head = new GitBranchSCMHead(branch);
        SCMRevision rev = e.commitHash() == null
                ? null
                : new AbstractGitSCMSource.SCMRevisionImpl(head, e.commitHash());
        return Collections.singletonMap(head, rev);
    }
}
