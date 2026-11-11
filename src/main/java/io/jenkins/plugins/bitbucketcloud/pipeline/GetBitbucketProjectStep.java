package io.jenkins.plugins.bitbucketcloud.pipeline;

import hudson.EnvVars;
import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.util.Map;
import java.util.Set;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * Returns the project of a Bitbucket Cloud repository as a map ({@code key}, {@code name}, {@code uuid}, {@code
 * description}, {@code isPrivate}); returns {@code null} if it cannot be read.
 */
public class GetBitbucketProjectStep extends Step {
    private String workspace;
    private String repo;

    @DataBoundConstructor
    public GetBitbucketProjectStep() {}

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
        return new Execution(context, workspace, repo);
    }

    private static class Execution extends SynchronousNonBlockingStepExecution<Map<String, Object>> {
        private static final long serialVersionUID = 1L;

        private final String workspace;
        private final String repo;

        Execution(StepContext context, String workspace, String repo) {
            super(context);
            this.workspace = workspace;
            this.repo = repo;
        }

        @Override
        protected Map<String, Object> run() throws Exception {
            return ProjectReadPublisher.read(
                    getContext().get(Run.class),
                    getContext().get(TaskListener.class),
                    getContext().get(EnvVars.class),
                    workspace,
                    repo);
        }
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {
        @Override
        public String getFunctionName() {
            return "getBitbucketProject";
        }

        @Override
        public String getDisplayName() {
            return "Get the project of a Bitbucket Cloud repository";
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Run.class, TaskListener.class, EnvVars.class);
        }
    }
}
