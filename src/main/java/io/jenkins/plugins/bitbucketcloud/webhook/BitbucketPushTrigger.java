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
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/** Builds the job when a Bitbucket Cloud webhook addressed to this job's own URL passes verification and filters. */
public class BitbucketPushTrigger extends Trigger<Job<?, ?>> {
    static final String DEFAULT_EVENTS = "repo:push";

    private static final SecureRandom RANDOM = new SecureRandom();

    private Secret secret;
    private String allowedEvents = DEFAULT_EVENTS;
    private String includeBranches = "";
    private String excludeBranches = "";

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

    public String getAllowedEvents() {
        return allowedEvents;
    }

    @DataBoundSetter
    public void setAllowedEvents(String allowedEvents) {
        this.allowedEvents = Util.fixEmptyAndTrim(allowedEvents) == null ? DEFAULT_EVENTS : allowedEvents.trim();
    }

    public String getIncludeBranches() {
        return includeBranches;
    }

    @DataBoundSetter
    public void setIncludeBranches(String includeBranches) {
        this.includeBranches = Util.fixNull(includeBranches).trim();
    }

    public String getExcludeBranches() {
        return excludeBranches;
    }

    @DataBoundSetter
    public void setExcludeBranches(String excludeBranches) {
        this.excludeBranches = Util.fixNull(excludeBranches).trim();
    }

    boolean verifySignature(byte[] body, String signatureHeader) {
        return SignatureVerifier.verify(getSecretText(), body, signatureHeader);
    }

    /** Returns the reason the event is rejected, or {@code null} when it should start a build. */
    String rejectionReason(WebhookEvent event) {
        if (!splitList(allowedEvents).contains(event.getEventKey())) {
            return "event " + event.getEventKey() + " is not allowed";
        }
        if (event.getRefs().isEmpty()) {
            return "no matching ref in payload";
        }
        return matchingRef(event) == null ? "no ref matches the branch filters" : null;
    }

    String matchingRef(WebhookEvent event) {
        List<Pattern> includes = toPatterns(includeBranches);
        List<Pattern> excludes = toPatterns(excludeBranches);
        for (String ref : event.getRefs()) {
            boolean included = includes.isEmpty() || includes.stream().anyMatch(p -> p.matcher(ref).matches());
            boolean excluded = excludes.stream().anyMatch(p -> p.matcher(ref).matches());
            if (included && !excluded) {
                return ref;
            }
        }
        return null;
    }

    /** Schedules a build and returns {@code false} when the job cannot be built right now. */
    boolean schedule(WebhookEvent event) {
        if (job == null || !job.isBuildable() || !(job instanceof ParameterizedJobMixIn.ParameterizedJob<?, ?> pj)) {
            return false;
        }
        BitbucketWebHookCause cause =
                new BitbucketWebHookCause(event.getEventKey(), event.getRepository(), matchingRef(event), event.getActor());
        ParameterizedJobMixIn<?, ?> mixIn = new ParameterizedJobMixIn() {
            @Override
            protected Job asJob() {
                return job;
            }
        };
        return mixIn.scheduleBuild2(pj.getQuietPeriod(), new CauseAction(cause)) != null;
    }

    private static List<String> splitList(String value) {
        List<String> parts = new ArrayList<>();
        for (String part : Util.fixNull(value).split("[\\s,]+")) {
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        return parts;
    }

    // '*' matches any characters (including '/'), '?' matches a single character.
    private static List<Pattern> toPatterns(String value) {
        List<Pattern> patterns = new ArrayList<>();
        for (String glob : splitList(value)) {
            StringBuilder regex = new StringBuilder();
            for (char c : glob.toCharArray()) {
                if (c == '*') {
                    regex.append(".*");
                } else if (c == '?') {
                    regex.append('.');
                } else {
                    regex.append(Pattern.quote(String.valueOf(c)));
                }
            }
            patterns.add(Pattern.compile(regex.toString()));
        }
        return patterns;
    }

    @Extension
    @Symbol("bitbucketPush")
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

        public String getDefaultEvents() {
            return DEFAULT_EVENTS;
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
