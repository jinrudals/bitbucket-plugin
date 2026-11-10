package io.jenkins.plugins.bitbucketcloud.pipeline;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.bitbucketcloud.api.CommitStatus;
import java.util.Set;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;
import org.jenkinsci.plugins.workflow.steps.GeneralNonBlockingStepExecution;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Wraps a block and reports its outcome: {@code INPROGRESS} when it starts, then {@code SUCCESSFUL},
 * {@code FAILED} or {@code STOPPED} (aborted) when it ends.
 */
public class BitbucketCommitStatusStep extends Step {
    private String name;
    private String key;
    private String description;
    private String url;
    private String workspace;
    private String repo;
    private String commit;

    @DataBoundConstructor
    public BitbucketCommitStatusStep() {}

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
                context, new CommitStatusOptions(name, key, description, url, workspace, repo, commit));
    }

    private static class Execution extends GeneralNonBlockingStepExecution {
        private static final long serialVersionUID = 1L;

        private final CommitStatusOptions options;

        Execution(StepContext context, CommitStatusOptions options) {
            super(context);
            this.options = options;
        }

        @Override
        public boolean start() throws Exception {
            run(() -> {
                CommitStatusPublisher.publish(
                        getContext().get(Run.class),
                        getContext().get(TaskListener.class),
                        getContext().get(EnvVars.class),
                        options,
                        CommitStatus.State.INPROGRESS);
                getContext().newBodyInvoker().withCallback(new Callback(options)).start();
            });
            return false;
        }
    }

    private static class Callback extends BodyExecutionCallback {
        private static final long serialVersionUID = 1L;

        private final CommitStatusOptions options;

        Callback(CommitStatusOptions options) {
            this.options = options;
        }

        @Override
        public void onSuccess(StepContext context, Object result) {
            finish(context, CommitStatus.State.SUCCESSFUL);
            context.onSuccess(result);
        }

        @Override
        public void onFailure(StepContext context, Throwable t) {
            boolean aborted = t instanceof FlowInterruptedException e && Result.ABORTED.equals(e.getResult());
            finish(context, aborted ? CommitStatus.State.STOPPED : CommitStatus.State.FAILED);
            context.onFailure(t);
        }

        private void finish(StepContext context, CommitStatus.State state) {
            try {
                CommitStatusPublisher.publish(
                        context.get(Run.class), context.get(TaskListener.class), context.get(EnvVars.class), options, state);
            } catch (AbortException e) {
                warn(context, e.getMessage());
            } catch (Exception e) {
                warn(context, e.toString());
            }
        }

        private static void warn(StepContext context, String message) {
            try {
                context.get(TaskListener.class).getLogger().println("[bitbucket] WARNING: " + message);
            } catch (Exception ignored) {
                // The build is already finishing; there is nowhere left to report to.
            }
        }
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {
        @Override
        public String getFunctionName() {
            return "bitbucketCommitStatus";
        }

        @Override
        public String getDisplayName() {
            return "Publish the Bitbucket Cloud commit status of a block";
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return true;
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Run.class, TaskListener.class, EnvVars.class);
        }
    }
}
