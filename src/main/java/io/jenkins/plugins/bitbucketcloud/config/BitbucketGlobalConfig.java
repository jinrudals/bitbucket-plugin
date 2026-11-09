package io.jenkins.plugins.bitbucketcloud.config;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import jenkins.model.GlobalConfiguration;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundSetter;

@Extension
@Symbol("bitbucketCloud")
public class BitbucketGlobalConfig extends GlobalConfiguration {
    private List<BitbucketWorkspaceConnection> workspaceConnections = new ArrayList<>();

    public BitbucketGlobalConfig() {
        load();
    }

    public static BitbucketGlobalConfig get() {
        return GlobalConfiguration.all().getInstance(BitbucketGlobalConfig.class);
    }

    public List<BitbucketWorkspaceConnection> getWorkspaceConnections() {
        return workspaceConnections == null ? List.of() : List.copyOf(workspaceConnections);
    }

    @DataBoundSetter
    public void setWorkspaceConnections(List<BitbucketWorkspaceConnection> workspaceConnections)
            throws Descriptor.FormException {
        List<BitbucketWorkspaceConnection> candidate =
                workspaceConnections == null ? List.of() : new ArrayList<>(workspaceConnections);
        FormValidation validation = validateWorkspaceConnections(candidate);
        if (validation.kind == FormValidation.Kind.ERROR) {
            throw new Descriptor.FormException(validation.getMessage(), "workspaceConnections");
        }
        this.workspaceConnections = List.copyOf(candidate);
        save();
    }

    public static FormValidation validateWorkspaceConnections(List<BitbucketWorkspaceConnection> connections) {
        if (connections == null) {
            return FormValidation.ok();
        }
        Set<String> seen = new HashSet<>();
        for (BitbucketWorkspaceConnection connection : connections) {
            if (connection == null || !BitbucketWorkspaceConnection.isValidWorkspaceSlug(connection.getWorkspace())) {
                return FormValidation.error("Enter a valid Bitbucket Cloud workspace slug.");
            }
            if (connection.getCredentialsId() == null || connection.getCredentialsId().isBlank()) {
                return FormValidation.error("Select a credential for workspace " + connection.getWorkspace() + ".");
            }
            String normalizedWorkspace = connection.getWorkspace().toLowerCase(Locale.ROOT);
            if (!seen.add(normalizedWorkspace)) {
                return FormValidation.error(
                        "Workspace " + connection.getWorkspace() + " is listed more than once.");
            }
        }
        return FormValidation.ok();
    }

}
