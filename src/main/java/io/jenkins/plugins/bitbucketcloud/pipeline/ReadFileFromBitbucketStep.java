package io.jenkins.plugins.bitbucketcloud.pipeline;

import hudson.EnvVars;
import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.util.Set;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/** Reads a file at a branch or commit of a Bitbucket Cloud repository; returns {@code null} if it is missing. */
public class ReadFileFromBitbucketStep extends Step {
    private final String path;
    private final String ref;
    private String workspace;
    private String repo;

    @DataBoundConstructor
    public ReadFileFromBitbucketStep(String path, String ref) {
        this.path = path;
        this.ref = ref;
    }

    public String getPath() {
        return path;
    }

    /** A branch name or a commit hash. */
    public String getRef() {
        return ref;
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
        return new Execution(context, workspace, repo, ref, path);
    }

    private static class Execution extends SynchronousNonBlockingStepExecution<String> {
        private static final long serialVersionUID = 1L;

        private final String workspace;
        private final String repo;
        private final String ref;
        private final String path;

        Execution(StepContext context, String workspace, String repo, String ref, String path) {
            super(context);
            this.workspace = workspace;
            this.repo = repo;
            this.ref = ref;
            this.path = path;
        }

        @Override
        protected String run() throws Exception {
            return FileReadPublisher.read(
                    getContext().get(Run.class),
                    getContext().get(TaskListener.class),
                    getContext().get(EnvVars.class),
                    workspace,
                    repo,
                    ref,
                    path);
        }
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {
        @Override
        public String getFunctionName() {
            return "readFileFromBitbucket";
        }

        @Override
        public String getDisplayName() {
            return "Read a file from Bitbucket Cloud";
        }

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Run.class, TaskListener.class, EnvVars.class);
        }
    }
}
