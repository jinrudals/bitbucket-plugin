package io.jenkins.plugins.bitbucketcloud.event;

public class InvalidPayloadException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidPayloadException(String message) {
        super(message);
    }

    public InvalidPayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
