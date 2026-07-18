package io.apicurio.registry.federation;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory registry of federated peers (POC #8424; production would persist these in storage).
 */
@ApplicationScoped
public class PeerRegistry {

    private final ConcurrentMap<String, FederatedPeer> peers = new ConcurrentHashMap<>();

    public FederatedPeer register(String name, String url) {
        String id = UUID.randomUUID().toString();
        FederatedPeer peer = new FederatedPeer(id, name, url, true);
        peers.put(id, peer);
        return peer;
    }

    public List<FederatedPeer> list() {
        return new ArrayList<>(peers.values());
    }

    public FederatedPeer get(String id) {
        return peers.get(id);
    }

    public boolean remove(String id) {
        return peers.remove(id) != null;
    }
}
