package io.jenkins.plugins.bitbucketcloud;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class PluginSmokeTest {

    @Test
    void jenkinsStarts(JenkinsRule j) {
        assertNotNull(j.jenkins.getPluginManager().getPlugin("bitbucket-cloud"));
    }
}
