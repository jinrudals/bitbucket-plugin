package io.jenkins.plugins.bitbucketcloud.pipeline;

import hudson.EnvVars;
import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.bitbucketcloud.api.PullRequestItemType;
import java.util.Set;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/** Creates a comment thread or a task on a Bitbucket Cloud pull request and returns its id. */
public class CommentOnBitbucketPullRequestStep extends Step {
    private final String content;
    private String type;
    private String parentId;
    private String pullRequestId;
    private String workspace;
    private String repo;

    @DataBoundConstructor
    public CommentOnBitbucketPullRequestStep(String content) {
        this.content = content;
    }

    public String getContent() {
        return content;
    }

    public String getType() {
        return type;
    }

    /** {@code THREAD} (default) or {@code TASK}. */
    @DataBoundSetter
    public void setType(String type) {
        this.type = type;
    }

    public String getParentId() {
        return parentId;
    }

    /** Id of a comment to reply to; only valid for threads. */
    @DataBoundSetter
    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    public String getPullRequestId() {
        return pullRequestId;
    }

    @DataBoundSetter
    public void setPullRequestId(String pullRequestId) {
        this.pullRequestId = pullRequestId;
    }

    public String getWorkspace() {
        return workspace;
    }

    @DataBoundSetter
    public void setWorkspace(String workspace) {
        this.workspace = workspace;
    }

    public String getRepo() {
        return repo;
    }

    @DataBoundSetter
    public void setRepo(String repo) {
        this.repo = repo;
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(
                context, type, new PullRequestCommentOptions(content, parentId, pullRequestId, workspace, repo));
    }

    private static class Execution extends SynchronousNonBlockingStepExecution<String> {
        private static final long serialVersionUID = 1L;

        private final String type;
        private final PullRequestCommentOptions options;

        Execution(StepContext context, String type, PullRequestCommentOptions options) {
            super(context);
            this.type = type;
            this.options = options;
        }

        @Override
        protected String run() throws Exception {
            PullRequestItemType parsed = PullRequestCommentPublisher.parseType(type);
            return PullRequestCommentPublisher.publish(
                    getContext().get(Run.class),
                    getContext().get(TaskListener.class),
                    getContext().get(EnvVars.class),
                    options,
                    parsed);
        }
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {
        @Override
        public String getFunctionName() {
            return "commentOnBitbucketPullRequest";
        }

        @Override
        public String getDisplayName() {
            return "Comment on a Bitbucket Cloud pull request";
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Run.class, TaskListener.class, EnvVars.class);
        }
    }
}
