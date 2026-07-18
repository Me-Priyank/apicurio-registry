package io.apicurio.registry.federation.rest.beans;

/**
 * Per-source summary in a federated search response: whether the source answered and how many it returned.
 */
public class FederatedSource {

    private String name;
    private String url;
    private boolean available;
    private long count;

    public FederatedSource() {
    }

    public FederatedSource(String name, String url, boolean available, long count) {
        this.name = name;
        this.url = url;
        this.available = available;
        this.count = count;
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

    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = count;
    }
}
