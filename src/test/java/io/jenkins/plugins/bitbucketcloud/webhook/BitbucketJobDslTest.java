package io.jenkins.plugins.bitbucketcloud.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import hudson.model.FreeStyleProject;
import javaposse.jobdsl.dsl.DslScriptLoader;
import javaposse.jobdsl.plugin.JenkinsJobManagement;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class BitbucketJobDslTest {
    @Test
    void triggersBlockConfiguresSecretAndMultipleRules(JenkinsRule j) throws Exception {
        String script = "job('dsl-job') {\n"
                + "  triggers {\n"
                + "    bitbucket {\n"
                + "      secretText('dsl-secret')\n"
                + "      rules {\n"
                + "        rule { events('repo:push'); includeBranches('main') }\n"
                + "        rule { events('pullrequest:created'); excludeBranches('wip/*') }\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "}\n";

        new DslScriptLoader(new JenkinsJobManagement(System.out, new java.util.HashMap<>(), new java.io.File(".")))
                .runScript(script);

        FreeStyleProject job = j.jenkins.getItemByFullName("dsl-job", FreeStyleProject.class);
        assertNotNull(job);
        BitbucketPushTrigger trigger = job.getTrigger(BitbucketPushTrigger.class);
        assertNotNull(trigger);
        assertEquals("dsl-secret", trigger.getSecretText());
        assertEquals(2, trigger.getRules().size());
        assertEquals("main", trigger.getRules().get(0).getIncludeBranches());
        assertEquals("pullrequest:created", trigger.getRules().get(1).getEvents());
    }
}
