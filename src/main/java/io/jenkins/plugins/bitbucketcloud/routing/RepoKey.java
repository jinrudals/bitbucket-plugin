package io.jenkins.plugins.bitbucketcloud.routing;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record RepoKey(String workspace, String slug) {
    private static final Pattern URL_FORM =
            Pattern.compile("^[a-z][a-z0-9+.-]*://(?:[^/@]*@)?([^/:]+)(?::\\d+)?/(.*)$");
    private static final Pattern SCP_FORM = Pattern.compile("^(?:[^/@:]+@)?([^/:]+):(.*)$");
    private static final Pattern PATH = Pattern.compile("^([^/]+)/([^/]+)$");
    private static final String HOST = "bitbucket.org";

    public static Optional<RepoKey> fromUrl(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        String trimmed = url.trim().toLowerCase(Locale.ROOT);
        Matcher m = URL_FORM.matcher(trimmed);
        if (!m.matches()) {
            m = SCP_FORM.matcher(trimmed);
            if (!m.matches()) {
                return Optional.empty();
            }
        }
        if (!HOST.equals(m.group(1))) {
            return Optional.empty();
        }
        String path = m.group(2).replaceAll("/+$", "");
        if (path.endsWith(".git")) {
            path = path.substring(0, path.length() - 4);
        }
        Matcher p = PATH.matcher(path);
        if (!p.matches() || p.group(2).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new RepoKey(p.group(1), p.group(2)));
    }

    public static RepoKey fromFullName(String fullName) {
        String[] parts = fullName.trim().toLowerCase(Locale.ROOT).split("/", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Expected workspace/slug: " + fullName);
        }
        return new RepoKey(parts[0], parts[1]);
    }
}
