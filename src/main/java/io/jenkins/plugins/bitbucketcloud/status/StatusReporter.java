package io.jenkins.plugins.bitbucketcloud.status;

import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionListener;

@Extension
public class StatusReporter extends RunListener<Run<?, ?>> {
    private static final Logger LOGGER = Logger.getLogger(StatusReporter.class.getName());
    private static final int MAX_KEY = 40;

    @Override
    public void onStarted(Run<?, ?> run, TaskListener listener) {
        report(run, "INPROGRESS", "Build started");
    }

    @Override
    public void onCompleted(Run<?, ?> run, TaskListener listener) {
        Result r = run.getResult();
        String state;
        if (r == null || r == Result.SUCCESS || r == Result.UNSTABLE) {
            state = "SUCCESSFUL";
        } else if (r == Result.ABORTED || r == Result.NOT_BUILT) {
            state = "STOPPED";
        } else {
            state = "FAILED";
        }
        report(run, state, "Build " + (r == null ? "SUCCESS" : r.toString()));
    }

    /**
     * Multibranch builds learn their revision only once the pipeline definition is loaded, which is after
     * {@link #onStarted}; report INPROGRESS from there when it could not be reported earlier.
     */
    @Extension
    public static class MultibranchStart extends FlowExecutionListener {
        @Override
        public void onRunning(FlowExecution execution) {
            try {
                if (execution.getOwner().getExecutable() instanceof Run<?, ?> run
                        && StatusPoster.hasMultibranchOnlyTarget(run)) {
                    ExtensionList.lookupSingleton(StatusReporter.class).report(run, "INPROGRESS", "Build started");
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.FINE, "Could not report multibranch build start", e);
            }
        }
    }

    private void report(Run<?, ?> run, String state, String description) {
        String fullName = run.getParent().getFullName();
        StatusPoster.post(run, statusKey(fullName), "Jenkins: " + fullName, state, description, run.getAbsoluteUrl());
    }

    // Bitbucket limits keys to 40 chars; long names keep a prefix plus a SHA-1 suffix to stay unique.
    static String statusKey(String fullName) {
        if (fullName.length() <= MAX_KEY) {
            return fullName;
        }
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(fullName.getBytes(StandardCharsets.UTF_8));
            return fullName.substring(0, MAX_KEY - 9) + "-" + HexFormat.of().formatHex(d, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
