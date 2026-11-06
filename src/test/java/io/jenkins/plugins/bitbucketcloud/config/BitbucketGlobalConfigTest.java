package io.jenkins.plugins.bitbucketcloud.config;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import hudson.util.Secret;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class BitbucketGlobalConfigTest {

    @Test
    void defaults(JenkinsRule j) {
        BitbucketGlobalConfig c = BitbucketGlobalConfig.get();
        assertFalse(c.isAllowUnsignedWebhooks());
        assertEquals("https://api.bitbucket.org", c.getApiBaseUrl());
        assertTrue(c.webhookSecret().isEmpty());
        c.setApiBaseUrl("  ");
        assertEquals("https://api.bitbucket.org", c.getApiBaseUrl());
    }

    @Test
    void roundTrip(JenkinsRule j) throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new StringCredentialsImpl(CredentialsScope.GLOBAL, "wh", "d", Secret.fromString("s3cret")));
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new StringCredentialsImpl(CredentialsScope.GLOBAL, "tok", "d", Secret.fromString("t")));
        BitbucketGlobalConfig c = BitbucketGlobalConfig.get();
        c.setWebhookSecretId("wh");
        c.setDefaultCredentialsId("tok");
        c.setAllowUnsignedWebhooks(true);
        c.setApiBaseUrl("http://localhost:1234");
        j.configRoundtrip();
        c = BitbucketGlobalConfig.get();
        assertEquals("wh", c.getWebhookSecretId());
        assertEquals("tok", c.getDefaultCredentialsId());
        assertTrue(c.isAllowUnsignedWebhooks());
        assertEquals("http://localhost:1234", c.getApiBaseUrl());
        assertEquals("s3cret", c.webhookSecret().orElseThrow());
    }
}
