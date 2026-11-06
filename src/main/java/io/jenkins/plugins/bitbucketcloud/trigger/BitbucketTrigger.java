package io.jenkins.plugins.bitbucketcloud.trigger;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.CauseAction;
import hudson.model.Item;
import hudson.model.Job;
import hudson.security.ACL;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.util.ListBoxModel;
import io.jenkins.plugins.bitbucketcloud.auth.BitbucketOAuthCredentials;
import io.jenkins.plugins.bitbucketcloud.event.BitbucketEvent;
import io.jenkins.plugins.bitbucketcloud.event.PullRequestEvent;
import io.jenkins.plugins.bitbucketcloud.event.PushEvent;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import jenkins.model.ParameterizedJobMixIn;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

public class BitbucketTrigger extends Trigger<Job<?, ?>> {

    private static final int DEDUPE_CAPACITY = 50;

    private boolean pushEvents = true;
    private boolean pullRequestCreated = true;
    private boolean pullRequestUpdated = true;
    private String sourceBranchIncludes;
    private String sourceBranchExcludes;
    private String targetBranchIncludes;
    private String targetBranchExcludes;
    private boolean ignoreDraft = true;
    private boolean allowForkPullRequests;
    private boolean skipDuplicateCommit = true;
    private String credentialsId;

    private transient Map<String, Boolean> recentCommits;

    @DataBoundConstructor
    public BitbucketTrigger() {}

    public boolean isPushEvents() {
        return pushEvents;
    }

    @DataBoundSetter
    public void setPushEvents(boolean pushEvents) {
        this.pushEvents = pushEvents;
    }

    public boolean isPullRequestCreated() {
        return pullRequestCreated;
    }

    @DataBoundSetter
    public void setPullRequestCreated(boolean pullRequestCreated) {
        this.pullRequestCreated = pullRequestCreated;
    }

    public boolean isPullRequestUpdated() {
        return pullRequestUpdated;
    }

    @DataBoundSetter
    public void setPullRequestUpdated(boolean pullRequestUpdated) {
        this.pullRequestUpdated = pullRequestUpdated;
    }

    public String getSourceBranchIncludes() {
        return sourceBranchIncludes;
    }

    @DataBoundSetter
    public void setSourceBranchIncludes(String v) {
        this.sourceBranchIncludes = v;
    }

    public String getSourceBranchExcludes() {
        return sourceBranchExcludes;
    }

    @DataBoundSetter
    public void setSourceBranchExcludes(String v) {
        this.sourceBranchExcludes = v;
    }

    public String getTargetBranchIncludes() {
        return targetBranchIncludes;
    }

    @DataBoundSetter
    public void setTargetBranchIncludes(String v) {
        this.targetBranchIncludes = v;
    }

    public String getTargetBranchExcludes() {
        return targetBranchExcludes;
    }

    @DataBoundSetter
    public void setTargetBranchExcludes(String v) {
        this.targetBranchExcludes = v;
    }

    public boolean isIgnoreDraft() {
        return ignoreDraft;
    }

    @DataBoundSetter
    public void setIgnoreDraft(boolean ignoreDraft) {
        this.ignoreDraft = ignoreDraft;
    }

    public boolean isAllowForkPullRequests() {
        return allowForkPullRequests;
    }

    @DataBoundSetter
    public void setAllowForkPullRequests(boolean allowForkPullRequests) {
        this.allowForkPullRequests = allowForkPullRequests;
    }

    public boolean isSkipDuplicateCommit() {
        return skipDuplicateCommit;
    }

    @DataBoundSetter
    public void setSkipDuplicateCommit(boolean skipDuplicateCommit) {
        this.skipDuplicateCommit = skipDuplicateCommit;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = credentialsId == null || credentialsId.isBlank() ? null : credentialsId;
    }

    public Job<?, ?> getJob() {
        return job;
    }

    public boolean accepts(BitbucketEvent e) {
        if (e instanceof PushEvent p) {
            return pushEvents && new BranchFilter(sourceBranchIncludes, sourceBranchExcludes).matches(p.branch());
        }
        if (e instanceof PullRequestEvent pr) {
            boolean typeEnabled =
                    switch (pr.type()) {
                        case "created" -> pullRequestCreated;
                        case "updated" -> pullRequestUpdated;
                        default -> false;
                    };
            return typeEnabled
                    && !(ignoreDraft && pr.draft())
                    && (allowForkPullRequests || !pr.fromFork())
                    && new BranchFilter(sourceBranchIncludes, sourceBranchExcludes).matches(pr.sourceBranch())
                    && new BranchFilter(targetBranchIncludes, targetBranchExcludes).matches(pr.targetBranch());
        }
        return false;
    }

    /** Queues a build for the event; returns true only if one was actually queued. */
    public boolean fire(BitbucketEvent e) {
        if (job == null) {
            return false;
        }
        String hash = e.commitHash();
        Map<String, Boolean> recent = recent();
        synchronized (recent) {
            if (skipDuplicateCommit && hash != null && recent.containsKey(hash)) {
                return false;
            }
            if (!(job instanceof ParameterizedJobMixIn.ParameterizedJob<?, ?> pj)) {
                return false;
            }
            Action[] actions = {new CauseAction(new BitbucketCause(e)), new BitbucketBuildAction(e)};
            if (pj.scheduleBuild2(0, actions) == null) {
                return false;
            }
            if (hash != null) {
                recent.put(hash, Boolean.TRUE);
            }
            return true;
        }
    }

    private Map<String, Boolean> recent() {
        synchronized (this) {
            if (recentCommits == null) {
                recentCommits = Collections.synchronizedMap(new LinkedHashMap<>() {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                        return size() > DEDUPE_CAPACITY;
                    }
                });
            }
            return recentCommits;
        }
    }

    @Extension
    @Symbol("bitbucketCloud")
    public static class DescriptorImpl extends TriggerDescriptor {

        @Override
        public boolean isApplicable(Item item) {
            return item instanceof ParameterizedJobMixIn.ParameterizedJob;
        }

        @Override
        public String getDisplayName() {
            return "Build when a change is pushed to Bitbucket Cloud";
        }

        @POST
        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath Item item, @QueryParameter String credentialsId) {
            StandardListBoxModel m = new StandardListBoxModel();
            if (item == null || !item.hasPermission(Item.CONFIGURE)) {
                return m.includeCurrentValue(credentialsId);
            }
            return m.includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM2,
                            item,
                            StandardCredentials.class,
                            URIRequirementBuilder.fromUri("https://bitbucket.org")
                                    .build(),
                            CredentialsMatchers.anyOf(
                                    CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class),
                                    CredentialsMatchers.instanceOf(StringCredentials.class),
                                    CredentialsMatchers.instanceOf(BitbucketOAuthCredentials.class)))
                    .includeCurrentValue(credentialsId);
        }
    }
}
