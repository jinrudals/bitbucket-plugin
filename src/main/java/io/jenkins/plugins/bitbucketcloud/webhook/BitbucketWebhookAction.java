package io.jenkins.plugins.bitbucketcloud.webhook;

import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Computer;
import hudson.model.UnprotectedRootAction;
import hudson.security.ACL;
import hudson.security.csrf.CrumbExclusion;
import io.jenkins.plugins.bitbucketcloud.config.BitbucketGlobalConfig;
import io.jenkins.plugins.bitbucketcloud.event.BitbucketEvent;
import io.jenkins.plugins.bitbucketcloud.event.EventParser;
import io.jenkins.plugins.bitbucketcloud.event.InvalidPayloadException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import jenkins.security.SecurityContextExecutorService;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

@Extension
public class BitbucketWebhookAction implements UnprotectedRootAction {
    static final String URL_NAME = "bitbucket-webhook";
    static final int MAX_BODY_BYTES = 5 * 1024 * 1024;
    private static final Logger LOGGER = Logger.getLogger(BitbucketWebhookAction.class.getName());

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return null;
    }

    @Override
    public String getUrlName() {
        return URL_NAME;
    }

    public void doDynamic(StaplerRequest req, StaplerResponse rsp) throws IOException {
        if (!"POST".equals(req.getMethod())) {
            rsp.setHeader("Allow", "POST");
            reply(rsp, 405, "POST required");
            return;
        }
        if (req.getContentLengthLong() > MAX_BODY_BYTES) {
            reply(rsp, 413, "Payload too large");
            return;
        }
        byte[] body = readBody(req.getInputStream());
        if (body == null) {
            reply(rsp, 413, "Payload too large");
            return;
        }
        BitbucketGlobalConfig config = BitbucketGlobalConfig.get();
        Optional<String> secret = config.webhookSecret();
        boolean verified =
                secret.isPresent() && SignatureVerifier.verify(body, req.getHeader("X-Hub-Signature"), secret.get());
        // Unsigned webhooks are tolerated only when no secret is configured at all.
        if (!verified && (secret.isPresent() || !config.isAllowUnsignedWebhooks())) {
            LOGGER.log(Level.FINE, "Rejected webhook: signature missing/invalid or no secret configured");
            reply(rsp, 401, "Unauthorized");
            return;
        }
        if (body.length == 0) {
            reply(rsp, 400, "Empty payload");
            return;
        }
        List<BitbucketEvent> events;
        try {
            events = EventParser.parse(req.getHeader("X-Event-Key"), new String(body, StandardCharsets.UTF_8));
        } catch (InvalidPayloadException e) {
            LOGGER.log(Level.FINE, "Rejected webhook: invalid payload");
            reply(rsp, 400, "Invalid payload");
            return;
        }
        if (events.isEmpty()) {
            reply(rsp, 200, "Ignored");
            return;
        }
        dispatchAsync(events);
        reply(rsp, 202, "Accepted");
    }

    private static byte[] readBody(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
        return bytes.length > MAX_BODY_BYTES ? null : bytes;
    }

    private static void dispatchAsync(List<BitbucketEvent> events) {
        List<EventDispatcher> dispatchers = ExtensionList.lookup(EventDispatcher.class);
        SecurityContextExecutorService executor = new SecurityContextExecutorService(Computer.threadPoolForRemoting);
        for (BitbucketEvent event : events) {
            for (EventDispatcher dispatcher : dispatchers) {
                executor.execute(() -> {
                    try (var ignored = ACL.as2(ACL.SYSTEM2)) {
                        dispatcher.dispatch(event);
                    } catch (Exception e) {
                        LOGGER.log(
                                Level.WARNING,
                                "Event dispatcher " + dispatcher.getClass().getName() + " failed: "
                                        + e.getClass().getName(),
                                e);
                    }
                });
            }
        }
    }

    private static void reply(StaplerResponse rsp, int status, String message) throws IOException {
        rsp.setStatus(status);
        rsp.setContentType("text/plain;charset=UTF-8");
        rsp.getWriter().println(message);
    }

    @Extension
    public static class WebhookCrumbExclusion extends CrumbExclusion {
        @Override
        public boolean process(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            String path = request.getPathInfo();
            if (path != null && (path.equals("/" + URL_NAME) || path.startsWith("/" + URL_NAME + "/"))) {
                chain.doFilter(request, response);
                return true;
            }
            return false;
        }
    }
}
