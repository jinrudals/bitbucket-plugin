package io.jenkins.plugins.bitbucketcloud.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class RepoKeyTest {
    private static final RepoKey EXPECTED = new RepoKey("ws", "repo");

    @Test
    void normalizesSupportedUrlForms() {
        for (String url : new String[] {
            "https://user@bitbucket.org/Ws/Repo.git/",
            "git@bitbucket.org:ws/repo.git",
            "ssh://git@bitbucket.org/ws/repo",
            "https://bitbucket.org/ws/repo"
        }) {
            assertEquals(Optional.of(EXPECTED), RepoKey.fromUrl(url), url);
        }
    }

    @Test
    void rejectsForeignHostsAndGarbage() {
        for (String url : new String[] {
            "https://github.com/ws/repo",
            "https://evilbitbucket.org/ws/repo",
            "https://bitbucket.org/onlyone",
            "garbage",
            "",
            "   ",
            null
        }) {
            assertTrue(RepoKey.fromUrl(url).isEmpty(), String.valueOf(url));
        }
    }

    @Test
    void fromFullNameLowercases() {
        assertEquals(EXPECTED, RepoKey.fromFullName("Ws/Repo"));
    }
}
