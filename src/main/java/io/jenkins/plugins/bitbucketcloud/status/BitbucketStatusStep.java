package io.jenkins.plugins.bitbucketcloud.status;

import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import java.util.Set;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/** Publishes a named commit status for the build's commit, e.g. {@code bitbucketStatus(name: 'lint', state: 'SUCCESSFUL')}. */
public class BitbucketStatusStep extends Step {
    static final Set<String> STATES = Set.of("INPROGRESS", "SUCCESSFUL", "FAILED", "STOPPED");

    private final String name;
    private final String state;
    private String description;
    private String url;

    @DataBoundConstructor
    public BitbucketStatusStep(String name, String state) {
        this.name = name;
        this.state = state;
    }

    public String getName() {
        return name;
    }

    public String getState() {
        return state;
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

    @Override
    public StepExecution start(StepContext context) {
        return new Execution(context, this);
    }

    private static final class Execution extends SynchronousNonBlockingStepExecution<Void> {
        private static final long serialVersionUID = 1L;
        private final transient BitbucketStatusStep step;

        Execution(StepContext context, BitbucketStatusStep step) {
            super(context);
            this.step = step;
        }

        @Override
        protected Void run() throws Exception {
            String name = step.name == null ? "" : step.name.trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("bitbucketStatus: 'name' must not be blank");
            }
            String state = step.state == null ? "" : step.state.trim();
            if (!STATES.contains(state)) {
                throw new IllegalArgumentException(
                        "bitbucketStatus: 'state' must be one of INPROGRESS, SUCCESSFUL, FAILED, STOPPED but was '"
                                + step.state + "'");
            }
            Run<?, ?> run = getContext().get(Run.class);
            TaskListener listener = getContext().get(TaskListener.class);
            String jobName = run.getParent().getFullName();
            String url = step.url == null || step.url.isBlank() ? run.getAbsoluteUrl() : step.url;
            boolean posted = StatusPoster.post(
                    run,
                    StatusReporter.statusKey(jobName + "/" + name),
                    "Jenkins: " + jobName + " / " + name,
                    state,
                    step.description,
                    url);
            if (!posted) {
                listener.getLogger().println("bitbucketStatus: could not post '" + name + "' to Bitbucket (see log)");
            }
            return null;
        }
    }

    @Extension
    @Symbol("bitbucketStatus")
    public static class DescriptorImpl extends StepDescriptor {
        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Set.of(Run.class, TaskListener.class);
        }

        @Override
        public String getFunctionName() {
            return "bitbucketStatus";
        }

        @Override
        public String getDisplayName() {
            return "Publish a Bitbucket Cloud commit status";
        }
    }
}
