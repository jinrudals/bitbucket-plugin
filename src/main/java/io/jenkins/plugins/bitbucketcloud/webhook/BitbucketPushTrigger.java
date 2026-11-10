package io.jenkins.plugins.bitbucketcloud.webhook;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import hudson.Extension;
import hudson.Util;
import hudson.model.CauseAction;
import hudson.model.Item;
import hudson.model.Job;
import hudson.triggers.Trigger;
import hudson.security.ACL;
import hudson.triggers.TriggerDescriptor;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

/** Builds the job when a Bitbucket Cloud webhook addressed to this job's own URL passes verification and filters. */
public class BitbucketPushTrigger extends Trigger<Job<?, ?>> {
    private static final SecureRandom RANDOM = new SecureRandom();

    private Secret secret;
    private String secretCredentialsId;
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

    public String getSecretCredentialsId() {
        return secretCredentialsId;
    }

    /** When set, the webhook secret is read from this Secret text credential instead of {@code secretText}. */
    @DataBoundSetter
    public void setSecretCredentialsId(String secretCredentialsId) {
        this.secretCredentialsId = Util.fixEmptyAndTrim(secretCredentialsId);
    }

    public List<BitbucketRule> getRules() {
        return List.copyOf(rules);
    }

    @DataBoundSetter
    public void setRules(List<BitbucketRule> rules) {
        this.rules = rules == null ? new ArrayList<>() : new ArrayList<>(rules);
    }

    boolean verifySignature(byte[] body, String signatureHeader) {
        String effectiveSecret = getSecretText();
        if (secretCredentialsId != null) {
            // Fail closed: a missing credential must not silently disable signature checking.
            StringCredentials credentials = CredentialsMatchers.firstOrNull(
                    CredentialsProvider.lookupCredentialsInItem(StringCredentials.class, job, ACL.SYSTEM2, List.of()),
                    CredentialsMatchers.withId(secretCredentialsId));
            if (credentials == null) {
                return false;
            }
            effectiveSecret = credentials.getSecret().getPlainText();
        }
        return SignatureVerifier.verify(effectiveSecret, body, signatureHeader);
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

        public ListBoxModel doFillSecretCredentialsIdItems(
                @AncestorInPath Item item, @QueryParameter String secretCredentialsId) {
            StandardListBoxModel model = new StandardListBoxModel();
            if (item == null && !Jenkins.get().hasPermission(Jenkins.ADMINISTER)
                    || item != null && !item.hasPermission(Item.CONFIGURE)) {
                return model.includeCurrentValue(secretCredentialsId);
            }
            return model.includeEmptyValue()
                    .includeAs(ACL.SYSTEM2, item, StringCredentials.class)
                    .includeCurrentValue(secretCredentialsId);
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
