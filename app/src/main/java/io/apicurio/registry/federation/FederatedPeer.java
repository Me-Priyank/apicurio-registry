package io.apicurio.registry.federation;

/**
 * A federated peer registry instance that this registry can query for agent cards.
 * POC (#8424): held in memory; a production implementation would persist peers and
 * carry optional credentials for authenticated server-to-server calls.
 */
public class FederatedPeer {

    private String id;
    private String name;
    private String url;
    private boolean enabled = true;

    public FederatedPeer() {
    }

    public FederatedPeer(String id, String name, String url, boolean enabled) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.enabled = enabled;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
