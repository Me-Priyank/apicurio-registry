package io.apicurio.registry.federation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apicurio.registry.a2a.rest.beans.AgentSearchResult;
import io.apicurio.registry.a2a.rest.beans.AgentSearchResults;
import io.apicurio.registry.federation.rest.beans.FederatedAgentSearchResult;
import io.apicurio.registry.federation.rest.beans.FederatedAgentSearchResults;
import io.apicurio.registry.federation.rest.beans.FederatedSource;
import io.apicurio.registry.rest.wellknown.WellKnownResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Runs an agent-card search across the local instance and all registered peers, merging the
 * results. Peer calls are concurrent and time-bounded; an unreachable peer yields partial
 * results (degraded=true) rather than failing the whole request. POC for #8424.
 */
@ApplicationScoped
public class FederatedAgentSearchService {

    private static final Logger log = LoggerFactory.getLogger(FederatedAgentSearchService.class);
    private static final Duration PEER_TIMEOUT = Duration.ofSeconds(3);
    private static final String LOCAL_SOURCE = "local";

    @Inject
    WellKnownResource localAgents;

    @Inject
    PeerRegistry peerRegistry;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(PEER_TIMEOUT)
            .build();

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public FederatedAgentSearchResults search(String name, List<String> skills, int offset, int limit) {
        List<FederatedSource> sources = new ArrayList<>();
        List<FederatedAgentSearchResult> merged = new ArrayList<>();
        boolean degraded = false;

        // Local source: queried in-process, so it is never treated as a remote failure.
        AgentSearchResults local = localAgents.searchAgents(name, skills, null, null, null, offset, limit);
        addResults(merged, LOCAL_SOURCE, local);
        sources.add(new FederatedSource(LOCAL_SOURCE, null, true, local.getCount()));

        // Peers: fan out concurrently, then collect with per-peer failure isolation.
        Map<FederatedPeer, CompletableFuture<AgentSearchResults>> futures = new LinkedHashMap<>();
        for (FederatedPeer peer : peerRegistry.list()) {
            if (peer.isEnabled()) {
                futures.put(peer, queryPeer(peer, name, skills, offset, limit));
            }
        }
        for (Map.Entry<FederatedPeer, CompletableFuture<AgentSearchResults>> entry : futures.entrySet()) {
            FederatedPeer peer = entry.getKey();
            try {
                AgentSearchResults result = entry.getValue().join();
                addResults(merged, peer.getName(), result);
                sources.add(new FederatedSource(peer.getName(), peer.getUrl(), true, result.getCount()));
            } catch (Exception ex) {
                degraded = true;
                sources.add(new FederatedSource(peer.getName(), peer.getUrl(), false, 0));
                log.warn("Federated peer '{}' ({}) unavailable: {}", peer.getName(), peer.getUrl(),
                        rootMessage(ex));
            }
        }

        FederatedAgentSearchResults out = new FederatedAgentSearchResults();
        out.setCount(merged.size());
        out.setAgents(merged);
        out.setSources(sources);
        out.setDegraded(degraded);
        return out;
    }

    private CompletableFuture<AgentSearchResults> queryPeer(FederatedPeer peer, String name,
            List<String> skills, int offset, int limit) {
        HttpRequest request = HttpRequest.newBuilder(buildPeerUri(peer.getUrl(), name, skills, offset, limit))
                .timeout(PEER_TIMEOUT)
                .GET()
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new CompletionException(new IOException("HTTP " + response.statusCode()));
                    }
                    try {
                        return mapper.readValue(response.body(), AgentSearchResults.class);
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                });
    }

    private void addResults(List<FederatedAgentSearchResult> merged, String source, AgentSearchResults results) {
        if (results == null || results.getAgents() == null) {
            return;
        }
        for (AgentSearchResult agent : results.getAgents()) {
            merged.add(new FederatedAgentSearchResult(source, agent));
        }
    }

    private URI buildPeerUri(String baseUrl, String name, List<String> skills, int offset, int limit) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        StringBuilder sb = new StringBuilder(base)
                .append("/.well-known/agents?offset=").append(offset).append("&limit=").append(limit);
        if (name != null && !name.isBlank()) {
            sb.append("&name=").append(enc(name));
        }
        if (skills != null) {
            for (String skill : skills) {
                if (skill != null && !skill.isBlank()) {
                    sb.append("&skill=").append(enc(skill));
                }
            }
        }
        return URI.create(sb.toString());
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}
