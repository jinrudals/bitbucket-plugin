package io.jenkins.plugins.bitbucketcloud.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.util.Secret;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class AuthTest {

    private static String header(BitbucketAuth a) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost/x"));
        a.applyTo(b);
        return b.build().headers().firstValue("Authorization").orElse(null);
    }

    @Test
    void basicAuthEncodesUsernameAndToken() {
        assertEquals("Basic dTpw", header(new BasicAuth("u", Secret.fromString("p"))));
    }

    @Test
    void resolverOnlyAcceptsBitbucketAuthenticationCredentials(JenkinsRule j) throws Exception {
        var store = SystemCredentialsProvider.getInstance().getCredentials();
        store.add(new BitbucketAuthenticationCredentials(CredentialsScope.GLOBAL, "ba", "d", "u", Secret.fromString("p")));
        store.add(new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, "up", "d", "u", "p"));
        assertInstanceOf(BasicAuth.class, AuthResolver.resolveWorkspaceCredential("ba").orElseThrow());
        assertEquals(Optional.empty(), AuthResolver.resolveWorkspaceCredential("up"));
        assertEquals(Optional.empty(), AuthResolver.resolveWorkspaceCredential("nope"));
        assertEquals(Optional.empty(), AuthResolver.resolveWorkspaceCredential(null));
    }
}
