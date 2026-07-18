package io.apicurio.registry.noprofile.federation;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.apicurio.registry.AbstractResourceTestBase;
import io.apicurio.registry.federation.rest.beans.FederatedAgentSearchResults;
import io.apicurio.registry.rest.client.models.CreateArtifact;
import io.apicurio.registry.rest.client.models.CreateVersion;
import io.apicurio.registry.rest.client.models.VersionContent;
import io.apicurio.registry.types.ArtifactType;
import io.apicurio.registry.types.ContentTypes;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for federated agent search (#8424): verifies that results merge across the
 * local instance and a remote peer, and that an unreachable peer degrades gracefully (partial
 * results) instead of failing the whole request.
 */
@QuarkusTest
@TestProfile(FederationTestProfile.class)
public class FederationResourceTest extends AbstractResourceTestBase {

    private static final String LOCAL_AGENT = "fed-local-agent";

    private static final String STUB_RESPONSE = """
            {"count":1,"agents":[{"artifactId":"remote-agent","name":"RemoteAgent","skills":["remote-skill"],"createdOn":0}]}
            """;

    private static final String AGENT_CARD_CONTENT = """
            {
                "name": "FedLocalAgent",
                "description": "A local agent for federation testing",
                "version": "1.0.0",
                "capabilities": { "streaming": false, "pushNotifications": false },
                "skills": [ { "id": "local-skill", "name": "Local Skill", "description": "A local skill", "tags": ["testing"] } ],
                "defaultInputModes": ["text"],
                "defaultOutputModes": ["text"]
            }
            """;

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private String serverRootUrl;

    @BeforeEach
    public void setUpFederation() {
        int port = ConfigProvider.getConfig().getValue("quarkus.http.test-port", Integer.class);
        serverRootUrl = "http://localhost:" + port;
    }

    @Test
    public void testFederatedSearchMergesResultsAndDegradesGracefully() throws Exception {
        createAgentCard("default", LOCAL_AGENT, AGENT_CARD_CONTENT);

        HttpServer stub = startStubPeer();
        try {
            registerPeer("stub", "http://localhost:" + stub.getAddress().getPort());

            // Healthy: local + reachable peer are merged, nothing degraded.
            FederatedAgentSearchResults merged = federatedSearch();
            assertFalse(merged.isDegraded(), "should not be degraded when all peers respond");
            assertTrue(hasAgent(merged, "local", LOCAL_AGENT), "local agent should be present");
            assertTrue(hasAgent(merged, "stub", "remote-agent"), "remote peer agent should be merged in");
            assertTrue(sourceAvailable(merged, "stub"), "stub peer should be reported available");

            // Add an unreachable peer: response is degraded but still returns the reachable sources.
            registerPeer("dead", "http://localhost:59999");
            FederatedAgentSearchResults degraded = federatedSearch();
            assertTrue(degraded.isDegraded(), "should be degraded when a peer is unreachable");
            assertFalse(sourceAvailable(degraded, "dead"), "dead peer should be marked unavailable");
            assertTrue(hasAgent(degraded, "local", LOCAL_AGENT), "local results should survive degradation");
            assertTrue(hasAgent(degraded, "stub", "remote-agent"), "reachable peer should survive degradation");
        } finally {
            stub.stop(0);
        }
    }

    private FederatedAgentSearchResults federatedSearch() throws Exception {
        String json = RestAssured.given().baseUri(serverRootUrl)
                .when().get("/apis/registry/v3/federation/agents/search")
                .then().statusCode(200)
                .extract().body().asString();
        return mapper.readValue(json, FederatedAgentSearchResults.class);
    }

    private void registerPeer(String name, String url) {
        RestAssured.given().baseUri(serverRootUrl)
                .contentType(ContentType.JSON)
                .body("{\"name\":\"" + name + "\",\"url\":\"" + url + "\"}")
                .when().post("/apis/registry/v3/federation/peers")
                .then().statusCode(201);
    }

    private void createAgentCard(String groupId, String artifactId, String content) throws Exception {
        CreateArtifact createArtifact = new CreateArtifact();
        createArtifact.setArtifactId(artifactId);
        createArtifact.setArtifactType(ArtifactType.AGENT_CARD);

        CreateVersion createVersion = new CreateVersion();
        VersionContent versionContent = new VersionContent();
        versionContent.setContent(content);
        versionContent.setContentType(ContentTypes.APPLICATION_JSON);
        createVersion.setContent(versionContent);
        createArtifact.setFirstVersion(createVersion);

        clientV3.groups().byGroupId(groupId).artifacts().post(createArtifact);
    }

    private HttpServer startStubPeer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/.well-known/agents", exchange -> {
            byte[] body = STUB_RESPONSE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        return server;
    }

    private static boolean hasAgent(FederatedAgentSearchResults results, String source, String artifactId) {
        return results.getAgents() != null && results.getAgents().stream()
                .anyMatch(a -> source.equals(a.getSource()) && a.getAgent() != null
                        && artifactId.equals(a.getAgent().getArtifactId()));
    }

    private static boolean sourceAvailable(FederatedAgentSearchResults results, String name) {
        return results.getSources() != null && results.getSources().stream()
                .anyMatch(s -> name.equals(s.getName()) && s.isAvailable());
    }
}
