package io.jenkins.plugins.bitbucketcloud.trigger;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Include/exclude branch matching; patterns are space/comma separated globs ({@code *} stays within a segment, {@code **} crosses '/'). */
public final class BranchFilter {

    private final List<Pattern> includes;
    private final List<Pattern> excludes;

    public BranchFilter(String includes, String excludes) {
        this.includes = compile(includes);
        this.excludes = compile(excludes);
    }

    public boolean matches(String branch) {
        if (branch == null) {
            return includes.isEmpty() && !anyMatch(excludes, "");
        }
        if (anyMatch(excludes, branch)) {
            return false;
        }
        return includes.isEmpty() || anyMatch(includes, branch);
    }

    private static boolean anyMatch(List<Pattern> patterns, String branch) {
        return patterns.stream().anyMatch(p -> p.matcher(branch).matches());
    }

    private static List<Pattern> compile(String spec) {
        List<Pattern> result = new ArrayList<>();
        if (spec == null) {
            return result;
        }
        for (String glob : spec.trim().split("[\\s,]+")) {
            if (!glob.isEmpty()) {
                result.add(Pattern.compile(toRegex(glob)));
            }
        }
        return result;
    }

    private static String toRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    sb.append(".*");
                    i++;
                } else {
                    sb.append("[^/]*");
                }
            } else if (c == '?') {
                sb.append("[^/]");
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return sb.toString();
    }
}
