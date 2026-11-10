package io.jenkins.plugins.bitbucketcloud.webhook;

import hudson.Extension;
import hudson.Util;
import hudson.model.CauseAction;
import hudson.model.Item;
import hudson.model.Job;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.util.Secret;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/** Builds the job when a Bitbucket Cloud webhook addressed to this job's own URL passes verification and filters. */
public class BitbucketPushTrigger extends Trigger<Job<?, ?>> {
    private static final SecureRandom RANDOM = new SecureRandom();

    private Secret secret;
    private List<BitbucketRule> rules = new ArrayList<>();

    @DataBoundConstructor
    public BitbucketPushTrigger() {
        this.secret = Secret.fromString(generateSecret());
    }

    static String generateSecret() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public String getSecretText() {
        return secret == null ? "" : secret.getPlainText();
    }

    @DataBoundSetter
    public void setSecretText(String secretText) {
        this.secret = Secret.fromString(Util.fixEmptyAndTrim(secretText) == null ? generateSecret() : secretText.trim());
    }

    public List<BitbucketRule> getRules() {
        return List.copyOf(rules);
    }

    @DataBoundSetter
    public void setRules(List<BitbucketRule> rules) {
        this.rules = rules == null ? new ArrayList<>() : new ArrayList<>(rules);
    }

    boolean verifySignature(byte[] body, String signatureHeader) {
        return SignatureVerifier.verify(getSecretText(), body, signatureHeader);
    }

    /** Returns the ref that satisfied the first matching rule, or {@code null} when no rule matches. */
    String matchingRef(WebhookEvent event) {
        // Without explicit rules the trigger behaves as a single default rule (pushes to any branch).
        List<BitbucketRule> effective = rules.isEmpty() ? List.of(new BitbucketRule()) : rules;
        for (BitbucketRule rule : effective) {
            String ref = rule.matchingRef(event);
            if (ref != null) {
                return ref;
            }
        }
        return null;
    }

    /** Schedules a build and returns {@code false} when the job cannot be built right now. */
    boolean schedule(WebhookEvent event, String ref) {
        if (job == null || !job.isBuildable() || !(job instanceof ParameterizedJobMixIn.ParameterizedJob<?, ?> pj)) {
            return false;
        }
        BitbucketWebHookCause cause =
                new BitbucketWebHookCause(event.getEventKey(), event.getRepository(), ref, event.getActor());
        ParameterizedJobMixIn<?, ?> mixIn = new ParameterizedJobMixIn() {
            @Override
            protected Job asJob() {
                return job;
            }
        };
        return mixIn.scheduleBuild2(
                        pj.getQuietPeriod(), new CauseAction(cause), new BitbucketEnvAction(event.environment(ref))) != null;
    }

    @Extension
    @Symbol("bitbucket")
    public static class DescriptorImpl extends TriggerDescriptor {
        @Override
        public boolean isApplicable(Item item) {
            return item instanceof Job && item instanceof ParameterizedJobMixIn.ParameterizedJob;
        }

        @Override
        public String getDisplayName() {
            return "Build when a Bitbucket Cloud webhook is received";
        }

        public String newSecret() {
            return generateSecret();
        }

        public String webhookUrl(Item item) {
            if (item == null) {
                return "";
            }
            String root = Jenkins.get().getRootUrl();
            StringBuilder url = new StringBuilder(root == null ? "JENKINS_URL/" : root).append("bitbucket");
            for (String segment : item.getFullName().split("/")) {
                url.append('/').append(Util.rawEncode(segment));
            }
            return url.toString();
        }
    }
}
