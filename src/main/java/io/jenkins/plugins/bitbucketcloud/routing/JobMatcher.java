package io.jenkins.plugins.bitbucketcloud.routing;

import hudson.model.Job;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.UserRemoteConfig;
import hudson.scm.SCM;
import hudson.security.ACL;
import hudson.triggers.Trigger;
import io.jenkins.plugins.bitbucketcloud.trigger.BitbucketTrigger;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.ParameterizedJobMixIn;
import jenkins.triggers.SCMTriggerItem;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;

/** Matches a job's {@link BitbucketTrigger} against a Bitbucket repository. */
public final class JobMatcher {

    private static final Logger LOGGER = Logger.getLogger(JobMatcher.class.getName());

    private JobMatcher() {}

    /** The job's {@link BitbucketTrigger}, if it has one. */
    public static Optional<BitbucketTrigger> triggerOf(Job<?, ?> job) {
        if (job instanceof ParameterizedJobMixIn.ParameterizedJob<?, ?> pj) {
            for (Trigger<?> t : pj.getTriggers().values()) {
                if (t instanceof BitbucketTrigger bt) {
                    return Optional.of(bt);
                }
            }
        }
        return Optional.empty();
    }

    /** The job's trigger if it targets {@code repo}: by its explicit repository, else by its Git SCM remotes. */
    public static Optional<BitbucketTrigger> findTrigger(Job<?, ?> job, RepoKey repo) {
        try (var ignored = ACL.as2(ACL.SYSTEM2)) {
            Optional<BitbucketTrigger> trigger = triggerOf(job);
            if (trigger.isEmpty()) {
                return Optional.empty();
            }
            Optional<RepoKey> explicit = trigger.get().explicitRepository();
            boolean match = explicit.isPresent() ? explicit.get().equals(repo) : matches(job, repo);
            return match ? trigger : Optional.empty();
        } catch (Exception | LinkageError ex) {
            LOGGER.log(Level.WARNING, "Could not match job " + job.getFullName(), ex);
            return Optional.empty();
        }
    }

    private static boolean matches(Job<?, ?> job, RepoKey repo) {
        Set<SCM> scms = new LinkedHashSet<>();
        SCMTriggerItem item = SCMTriggerItem.SCMTriggerItems.asSCMTriggerItem(job);
        if (item != null) {
            Collection<? extends SCM> found = item.getSCMs();
            scms.addAll(found);
        }
        if (job instanceof WorkflowJob wj && wj.getDefinition() instanceof CpsScmFlowDefinition def) {
            scms.add(def.getScm());
        }
        for (SCM scm : scms) {
            if (scm instanceof GitSCM git && matches(git, repo)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(GitSCM git, RepoKey repo) {
        for (UserRemoteConfig rc : git.getUserRemoteConfigs()) {
            String url = rc.getUrl();
            if (url != null
                    && url.indexOf('$') < 0
                    && RepoKey.fromUrl(url).filter(repo::equals).isPresent()) {
                return true;
            }
        }
        return false;
    }
}
