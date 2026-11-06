package io.jenkins.plugins.bitbucketcloud.webhook;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class SignatureVerifierTest {
    private static final byte[] BODY = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
    private static final String SECRET = "s3cret";

    private static String sign(byte[] body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }

    @Test
    void acceptsValidSignature() throws Exception {
        assertTrue(SignatureVerifier.verify(BODY, "sha256=" + sign(BODY, SECRET), SECRET));
    }

    @Test
    void rejectsTamperedBodyAndWrongSecret() throws Exception {
        String header = "sha256=" + sign(BODY, SECRET);
        assertFalse(SignatureVerifier.verify("{\"a\":2}".getBytes(StandardCharsets.UTF_8), header, SECRET));
        assertFalse(SignatureVerifier.verify(BODY, header, "other"));
    }

    @Test
    void rejectsMalformedInputWithoutThrowing() throws Exception {
        String hex = sign(BODY, SECRET);
        assertFalse(SignatureVerifier.verify(BODY, null, SECRET));
        assertFalse(SignatureVerifier.verify(BODY, "  ", SECRET));
        assertFalse(SignatureVerifier.verify(BODY, hex, SECRET));
        assertFalse(SignatureVerifier.verify(BODY, "sha1=" + hex, SECRET));
        assertFalse(SignatureVerifier.verify(BODY, "sha256=zzzz", SECRET));
        assertFalse(SignatureVerifier.verify(BODY, "sha256=abc", SECRET));
        assertFalse(SignatureVerifier.verify(BODY, "sha256=", SECRET));
        assertFalse(SignatureVerifier.verify(BODY, "sha256=" + hex, null));
        assertFalse(SignatureVerifier.verify(BODY, "sha256=" + hex, ""));
        assertFalse(SignatureVerifier.verify(null, "sha256=" + hex, SECRET));
    }
}
