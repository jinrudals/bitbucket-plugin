package io.jenkins.plugins.bitbucketcloud.trigger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BranchFilterTest {

    @Test
    void blankIncludesMatchesAll() {
        assertTrue(new BranchFilter("", null).matches("anything"));
        assertTrue(new BranchFilter(null, null).matches("a/b"));
    }

    @Test
    void includeLimitsBranches() {
        BranchFilter f = new BranchFilter("main", "");
        assertTrue(f.matches("main"));
        assertFalse(f.matches("develop"));
    }

    @Test
    void excludeWins() {
        BranchFilter f = new BranchFilter("**", "wip/**");
        assertTrue(f.matches("main"));
        assertFalse(f.matches("wip/x"));
        assertFalse(new BranchFilter("main", "main").matches("main"));
    }

    @Test
    void commaAndSpaceSeparated() {
        BranchFilter f = new BranchFilter("main, release/* hotfix", "");
        assertTrue(f.matches("main"));
        assertTrue(f.matches("release/1"));
        assertTrue(f.matches("hotfix"));
        assertFalse(f.matches("release/1/x"));
    }

    @Test
    void doubleStarCrossesSlashes() {
        BranchFilter f = new BranchFilter("feature/**", "");
        assertTrue(f.matches("feature/a"));
        assertTrue(f.matches("feature/a/b"));
        assertFalse(f.matches("bugfix/a"));
    }

    @Test
    void nullBranch() {
        assertFalse(new BranchFilter("main", "").matches(null));
        assertTrue(new BranchFilter("", "x").matches(null));
    }
}
