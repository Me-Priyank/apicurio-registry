package io.apicurio.registry.federation.rest.beans;

/**
 * Request body for registering a federated peer (POST /apis/registry/v3/federation/peers).
 */
public class RegisterPeerRequest {

    private String name;
    private String url;

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
}
