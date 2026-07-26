package io.apicurio.registry.mcptools.rest.beans;

import java.util.List;

/**
 * A single MCP tool that is chaining-compatible with a given source tool: the source tool's output
 * can be fed as this tool's input. Carries enough explanation for a UI to show <em>why</em> the
 * tools chain.
 */
public class CompatibleMcpToolResult {

    private String groupId;
    private String artifactId;
    private String name;
    private String title;
    private String description;
    private List<String> matchedParameters;
    private List<String> optionalParameters;

    public CompatibleMcpToolResult() {
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public void setArtifactId(String artifactId) {
        this.artifactId = artifactId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    /**
     * @return required input parameters of this tool that the source tool's output satisfies.
     */
    public List<String> getMatchedParameters() {
        return matchedParameters;
    }

    public void setMatchedParameters(List<String> matchedParameters) {
        this.matchedParameters = matchedParameters;
    }

    /**
     * @return optional input parameters of this tool that the source tool's output also supplies.
     */
    public List<String> getOptionalParameters() {
        return optionalParameters;
    }

    public void setOptionalParameters(List<String> optionalParameters) {
        this.optionalParameters = optionalParameters;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final CompatibleMcpToolResult result = new CompatibleMcpToolResult();

        public Builder groupId(String groupId) {
            result.groupId = groupId;
            return this;
        }

        public Builder artifactId(String artifactId) {
            result.artifactId = artifactId;
            return this;
        }

        public Builder name(String name) {
            result.name = name;
            return this;
        }

        public Builder title(String title) {
            result.title = title;
            return this;
        }

        public Builder description(String description) {
            result.description = description;
            return this;
        }

        public Builder matchedParameters(List<String> matchedParameters) {
            result.matchedParameters = matchedParameters;
            return this;
        }

        public Builder optionalParameters(List<String> optionalParameters) {
            result.optionalParameters = optionalParameters;
            return this;
        }

        public CompatibleMcpToolResult build() {
            return result;
        }
    }
}
