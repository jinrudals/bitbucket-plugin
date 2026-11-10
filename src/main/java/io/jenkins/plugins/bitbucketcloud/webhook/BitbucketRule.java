package io.jenkins.plugins.bitbucketcloud.webhook;

import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

/**
 * One set of conditions a webhook delivery can satisfy. A trigger builds when any of its rules matches; new
 * conditions are added here without touching the trigger.
 */
public class BitbucketRule extends AbstractDescribableImpl<BitbucketRule> {
    static final String DEFAULT_EVENTS = "repo:push";

    private String events = DEFAULT_EVENTS;
    private String includeBranches = "";
    private String excludeBranches = "";

    @DataBoundConstructor
    public BitbucketRule() {}

    public String getEvents() {
        return events;
    }

    @DataBoundSetter
    public void setEvents(String events) {
        this.events = Util.fixEmptyAndTrim(events) == null ? DEFAULT_EVENTS : events.trim();
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

    /** Returns the first ref that satisfies this rule, or {@code null} when the event does not match. */
    String matchingRef(WebhookEvent event) {
        if (!splitList(events).contains(event.getEventKey())) {
            return null;
        }
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
    @Symbol("rule")
    public static class DescriptorImpl extends Descriptor<BitbucketRule> {
        @Override
        public String getDisplayName() {
            return "Rule";
        }

        public String getDefaultEvents() {
            return DEFAULT_EVENTS;
        }
    }
}
