package io.apicurio.registry.mcptools.rest.beans;

import java.util.List;

/**
 * Results of a "compatible tools" query: the MCP tools whose input can be driven by a given source
 * tool's output.
 */
public class CompatibleMcpToolResults {

    private long count;
    private List<CompatibleMcpToolResult> tools;

    public CompatibleMcpToolResults() {
    }

    public CompatibleMcpToolResults(long count, List<CompatibleMcpToolResult> tools) {
        this.count = count;
        this.tools = tools;
    }

    /**
     * @return the total number of compatible tools found (before pagination).
     */
    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = count;
    }

    public List<CompatibleMcpToolResult> getTools() {
        return tools;
    }

    public void setTools(List<CompatibleMcpToolResult> tools) {
        this.tools = tools;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private long count;
        private List<CompatibleMcpToolResult> tools;

        public Builder count(long count) {
            this.count = count;
            return this;
        }

        public Builder tools(List<CompatibleMcpToolResult> tools) {
            this.tools = tools;
            return this;
        }

        public CompatibleMcpToolResults build() {
            return new CompatibleMcpToolResults(count, tools);
        }
    }
}
