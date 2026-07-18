package io.apicurio.registry.federation.rest.beans;

import java.util.List;

/**
 * Consolidated federated search response. {@code degraded} is true when one or more peers
 * failed to respond, in which case the results are partial (the reachable sources only).
 */
public class FederatedAgentSearchResults {

    private long count;
    private boolean degraded;
    private List<FederatedSource> sources;
    private List<FederatedAgentSearchResult> agents;

    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = count;
    }

    public boolean isDegraded() {
        return degraded;
    }

    public void setDegraded(boolean degraded) {
        this.degraded = degraded;
    }

    public List<FederatedSource> getSources() {
        return sources;
    }

    public void setSources(List<FederatedSource> sources) {
        this.sources = sources;
    }

    public List<FederatedAgentSearchResult> getAgents() {
        return agents;
    }

    public void setAgents(List<FederatedAgentSearchResult> agents) {
        this.agents = agents;
    }
}
