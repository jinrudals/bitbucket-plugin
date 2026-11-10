package io.jenkins.plugins.bitbucketcloud.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Verifies the {@code X-Hub-Signature: sha256=<hex>} header Bitbucket Cloud sends for secured webhooks. */
final class SignatureVerifier {
    private static final String PREFIX = "sha256=";
    private static final String ALGORITHM = "HmacSHA256";

    private SignatureVerifier() {}

    static boolean verify(String secret, byte[] body, String header) {
        if (secret == null || secret.isEmpty() || header == null || !header.startsWith(PREFIX)) {
            return false;
        }
        byte[] expected = hmac(secret, body);
        byte[] actual;
        try {
            actual = HexFormat.of().parseHex(header.substring(PREFIX.length()).trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(expected, actual);
    }

    static String sign(String secret, byte[] body) {
        return PREFIX + HexFormat.of().formatHex(hmac(secret, body));
    }

    private static byte[] hmac(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
