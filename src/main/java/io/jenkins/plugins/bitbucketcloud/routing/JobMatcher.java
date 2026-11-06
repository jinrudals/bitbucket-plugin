package io.jenkins.plugins.bitbucketcloud.routing;

import hudson.model.Job;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.UserRemoteConfig;
import hudson.scm.SCM;
import hudson.security.ACL;
import hudson.triggers.Trigger;
import io.jenkins.plugins.bitbucketcloud.trigger.BitbucketTrigger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import jenkins.triggers.SCMTriggerItem;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;

/** Finds {@link BitbucketTrigger}s of jobs whose Git remotes point at a given Bitbucket repository. */
public final class JobMatcher {

    private static final Logger LOGGER = Logger.getLogger(JobMatcher.class.getName());

    private JobMatcher() {}

    public static List<BitbucketTrigger> findTriggers(RepoKey repo) {
        List<BitbucketTrigger> result = new ArrayList<>();
        try (var ignored = ACL.as2(ACL.SYSTEM2)) {
            for (Job<?, ?> job : Jenkins.get().allItems(Job.class)) {
                try {
                    collect(job, repo, result);
                } catch (Exception | LinkageError ex) {
                    LOGGER.log(Level.WARNING, "Skipping job " + job.getFullName() + " while matching", ex);
                }
            }
        }
        return result;
    }

    private static void collect(Job<?, ?> job, RepoKey repo, List<BitbucketTrigger> result) {
        if (!(job instanceof ParameterizedJobMixIn.ParameterizedJob<?, ?> pj)) {
            return;
        }
        List<BitbucketTrigger> triggers = new ArrayList<>();
        for (Trigger<?> t : pj.getTriggers().values()) {
            if (t instanceof BitbucketTrigger bt) {
                triggers.add(bt);
            }
        }
        if (triggers.isEmpty()) {
            return;
        }
        Boolean scmMatch = null;
        for (BitbucketTrigger bt : triggers) {
            Optional<RepoKey> explicit = bt.explicitRepository();
            if (explicit.isPresent()) {
                if (explicit.get().equals(repo)) {
                    result.add(bt);
                }
                continue;
            }
            if (scmMatch == null) {
                scmMatch = matches(job, repo);
            }
            if (scmMatch) {
                result.add(bt);
            }
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
