package io.jenkins.plugins.bitbucketcloud.webhook;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.security.csrf.CrumbExclusion;
import hudson.model.UnprotectedRootAction;
import hudson.triggers.Trigger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

/**
 * Receives Bitbucket Cloud webhooks at {@code /bitbucket/<folder>/<job>}; the path alone selects the one job to build.
 */
@Extension
public class BitbucketWebHook implements UnprotectedRootAction {
    public static final String URL_NAME = "bitbucket";
    static final int MAX_BODY_BYTES = 5 * 1024 * 1024;

    private static final Logger LOGGER = Logger.getLogger(BitbucketWebHook.class.getName());

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

    // A web method (rather than getDynamic) so that Stapler treats the response as served and adds no redirect.
    public void doDynamic(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        handle(req).generateResponse(req, rsp, this);
    }

    private HttpResponse handle(StaplerRequest req) throws IOException {
        Iterator<String> rest = Arrays.stream(req.getRestOfPath().split("/"))
                .filter(s -> !s.isEmpty())
                .iterator();
        if (!rest.hasNext()) {
            return HttpResponses.notFound();
        }
        Job<?, ?> job = resolveJob(rest.next(), rest);
        if (job == null) {
            return HttpResponses.notFound();
        }
        if (!"POST".equals(req.getMethod())) {
            return HttpResponses.error(405, "POST required");
        }
        BitbucketPushTrigger trigger = findTrigger(job);
        if (trigger == null) {
            return HttpResponses.text("skipped: trigger is not enabled for this job");
        }
        byte[] body = req.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            return HttpResponses.error(413, "Payload too large");
        }
        if (!trigger.verifySignature(body, req.getHeader("X-Hub-Signature"))) {
            return HttpResponses.forbidden();
        }
        String eventKey = req.getHeader("X-Event-Key");
        if (eventKey == null || eventKey.isBlank()) {
            return HttpResponses.error(400, "Missing X-Event-Key header");
        }
        WebhookEvent event;
        try {
            event = WebhookEvent.parse(eventKey, new String(body, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return HttpResponses.error(400, "Invalid JSON payload");
        }
        String reason = trigger.rejectionReason(event);
        if (reason != null) {
            LOGGER.log(Level.FINE, "Skipping webhook for {0}: {1}", new Object[] {job.getFullName(), reason});
            return HttpResponses.text("skipped: " + reason);
        }
        if (!trigger.schedule(event)) {
            return HttpResponses.text("skipped: job is not buildable");
        }
        return HttpResponses.text("triggered");
    }

    private static BitbucketPushTrigger findTrigger(Job<?, ?> job) {
        if (job instanceof ParameterizedJobMixIn.ParameterizedJob<?, ?> pj) {
            for (Trigger<?> trigger : pj.getTriggers().values()) {
                if (trigger instanceof BitbucketPushTrigger bitbucketTrigger) {
                    return bitbucketTrigger;
                }
            }
        }
        return null;
    }

    static Job<?, ?> resolveJob(String firstSegment, Iterator<String> rest) {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return null;
        }
        try (ACLContext ignored = ACL.as2(ACL.SYSTEM2)) {
            Item item = jenkins.getItemByFullName(firstSegment);
            while (item instanceof ItemGroup<?> group && !(item instanceof Job) && rest.hasNext()) {
                item = jenkins.getItem(rest.next(), group);
            }
            return item instanceof Job<?, ?> job ? job : null;
        }
    }

    @Extension
    public static class WebHookCrumbExclusion extends CrumbExclusion {
        @Override
        public boolean process(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
                throws IOException, ServletException {
            String pathInfo = req.getPathInfo();
            if (pathInfo != null && pathInfo.startsWith('/' + URL_NAME + '/')) {
                chain.doFilter(req, resp);
                return true;
            }
            return false;
        }
    }
}
