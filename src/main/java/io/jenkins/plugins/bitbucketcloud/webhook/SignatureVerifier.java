package io.jenkins.plugins.bitbucketcloud.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class SignatureVerifier {
    private static final String PREFIX = "sha256=";
    private static final String ALGORITHM = "HmacSHA256";

    private SignatureVerifier() {}

    public static boolean verify(byte[] body, String header, String secret) {
        if (body == null || header == null || secret == null || secret.isEmpty()) {
            return false;
        }
        String value = header.trim();
        if (!value.startsWith(PREFIX)) {
            return false;
        }
        try {
            byte[] provided = HexFormat.of().parseHex(value.substring(PREFIX.length()));
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return MessageDigest.isEqual(mac.doFinal(body), provided);
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            return false;
        }
    }
}
