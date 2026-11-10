package io.jenkins.plugins.bitbucketcloud.pipeline;

import hudson.EnvVars;
import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.bitbucketcloud.api.CommitStatus;
import java.util.Set;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/** Publishes a single build status for a commit to Bitbucket Cloud. */
public class UpdateBitbucketCommitStatusStep extends Step {
    private final String state;
    private String name;
    private String key;
    private String description;
    private String url;
    private String workspace;
    private String repo;
    private String commit;

    @DataBoundConstructor
    public UpdateBitbucketCommitStatusStep(String state) {
        this.state = state;
    }

    public String getState() {
        return state;
    }

    public String getName() {
        return name;
    }

    @DataBoundSetter
    public void setName(String name) {
        this.name = name;
    }

    public String getKey() {
        return key;
    }

    @DataBoundSetter
    public void setKey(String key) {
        this.key = key;
    }

    public String getDescription() {
        return description;
    }

    @DataBoundSetter
    public void setDescription(String description) {
        this.description = description;
    }

    public String getUrl() {
        return url;
    }

    @DataBoundSetter
    public void setUrl(String url) {
        this.url = url;
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

    public String getCommit() {
        return commit;
    }

    @DataBoundSetter
    public void setCommit(String commit) {
        this.commit = commit;
    }

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(
                context,
                state,
                new CommitStatusOptions(name, key, description, url, workspace, repo, commit));
    }

    private static class Execution extends SynchronousNonBlockingStepExecution<Void> {
        private static final long serialVersionUID = 1L;

        private final String state;
        private final CommitStatusOptions options;

        Execution(StepContext context, String state, CommitStatusOptions options) {
            super(context);
            this.state = state;
            this.options = options;
        }

        @Override
        protected Void run() throws Exception {
            CommitStatus.State parsed = CommitStatusPublisher.parseState(state);
            CommitStatusPublisher.publish(
                    getContext().get(Run.class),
                    getContext().get(TaskListener.class),
                    getContext().get(EnvVars.class),
                    options,
                    parsed);
            return null;
        }
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {
        @Override
        public String getFunctionName() {
            return "updateBitbucketCommitStatus";
        }

        @Override
        public String getDisplayName() {
            return "Update the Bitbucket Cloud commit status";
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Run.class, TaskListener.class, EnvVars.class);
        }
    }
}
