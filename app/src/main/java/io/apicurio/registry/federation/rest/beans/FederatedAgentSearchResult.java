package io.apicurio.registry.federation.rest.beans;

import io.apicurio.registry.a2a.rest.beans.AgentSearchResult;

/**
 * A single agent result tagged with the source (local or peer name) it came from.
 */
public class FederatedAgentSearchResult {

    private String source;
    private AgentSearchResult agent;

    public FederatedAgentSearchResult() {
    }

    public FederatedAgentSearchResult(String source, AgentSearchResult agent) {
        this.source = source;
        this.agent = agent;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public AgentSearchResult getAgent() {
        return agent;
    }

    public void setAgent(AgentSearchResult agent) {
        this.agent = agent;
    }
}
