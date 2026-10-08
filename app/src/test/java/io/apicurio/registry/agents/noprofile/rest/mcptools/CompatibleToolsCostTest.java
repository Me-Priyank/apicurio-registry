package io.apicurio.registry.agents.noprofile.rest.mcptools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apicurio.registry.AbstractResourceTestBase;
import io.apicurio.registry.agents.mcptools.compatibility.CrossToolCompatibilityService;
import io.apicurio.registry.agents.mcptools.compatibility.PreparedProducer;
import io.apicurio.registry.cdi.Current;
import io.apicurio.registry.model.GA;
import io.apicurio.registry.model.GAV;
import io.apicurio.registry.model.VersionExpressionParser;
import io.apicurio.registry.storage.RegistryStorage;
import io.apicurio.registry.storage.RegistryStorage.RetrievalBehavior;
import io.apicurio.registry.storage.dto.ArtifactSearchResultsDto;
import io.apicurio.registry.storage.dto.OrderBy;
import io.apicurio.registry.storage.dto.OrderDirection;
import io.apicurio.registry.storage.dto.SearchFilter;
import io.apicurio.registry.storage.dto.SearchedArtifactDto;
import io.apicurio.registry.storage.dto.StoredArtifactVersionDto;
import io.apicurio.registry.agents.noprofile.rest.a2a.ExperimentalFeaturesEnabledProfile;
import io.apicurio.registry.rest.client.models.CreateArtifact;
import io.apicurio.registry.rest.client.models.CreateVersion;
import io.apicurio.registry.rest.client.models.VersionContent;
import io.apicurio.registry.types.ArtifactType;
import io.apicurio.registry.types.ContentTypes;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.List;

/**
 * Temporary end-to-end cost measurement for #8427 / #10427. Not a real test.
 *
 * Producers are registered first, so at the cap the newest 500 MCP tools are all consumers and
 * each request evaluates exactly 500 candidates.
 */
@QuarkusTest
@TestProfile(ExperimentalFeaturesEnabledProfile.class)
public class CompatibleToolsCostTest extends AbstractResourceTestBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String GROUP = "cost";
    private static final int WARMUP = 5;
    private static final int TIMED = 20;

    @Inject
    @Current
    RegistryStorage storage;

    @Inject
    CrossToolCompatibilityService crossTool;

    @Test
    void cost() throws Exception {
        Path dir = Path.of(System.getProperty("cost.snaps"));
        Path out = Path.of(System.getProperty("cost.out"));
        int port = ConfigProvider.getConfig().getValue("quarkus.http.test-port", Integer.class);
        String root = "http://localhost:" + port;

        List<JsonNode> tools = new ArrayList<>();
        try (var files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".snap")).sorted().toList()) {
                tools.add(MAPPER.readTree(Files.readString(p)));
            }
        }
        List<JsonNode> bySize = new ArrayList<>(tools);
        bySize.sort(Comparator.comparingInt(t -> t.get("inputSchema").toString().length()));
        JsonNode[] chosen = { bySize.get(0), bySize.get(bySize.size() / 2), bySize.get(bySize.size() - 1) };
        String[] labels = { "smallest", "median", "largest" };

        JsonNode[] producerDocs = new JsonNode[chosen.length];
        for (int i = 0; i < chosen.length; i++) {
            ObjectNode producer = chosen[i].deepCopy();
            producer.put("name", "producer_" + labels[i]);
            producer.set("outputSchema", chosen[i].get("inputSchema").deepCopy());
            register("producer-" + labels[i], producer.toString());
            producerDocs[i] = producer;
        }

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            w.println("storage=H2 in-memory (test profile), same JVM, HTTP over localhost");
            w.println("availableProcessors=" + Runtime.getRuntime().availableProcessors() + " java="
                    + System.getProperty("java.version"));
            for (int i = 0; i < chosen.length; i++) {
                w.println("producer " + labels[i] + ": " + chosen[i].get("name").asText() + ", outputSchema "
                        + chosen[i].get("inputSchema").toString().length() + " bytes");
            }
            w.println();

            registerConsumers(tools, 0, tools.size());
            measure(w, root, labels, "125 consumers + 3 producers registered, 127 candidates per request");

            registerConsumers(tools, tools.size(), 500);
            measure(w, root, labels, "500 consumers + 3 producers registered, 500 candidates per request (cap)");
            decompose(w, root, labels, producerDocs);
        }
    }

    private void registerConsumers(List<JsonNode> tools, int from, int to) throws Exception {
        for (int i = from; i < to; i++) {
            ObjectNode consumer = tools.get(i % tools.size()).deepCopy();
            consumer.put("name", consumer.get("name").asText() + "_" + i);
            register("c-" + i, consumer.toString());
        }
    }

    private void measure(PrintWriter w, String root, String[] labels, String phase) {
        w.println("== " + phase);
        for (String label : labels) {
            String path = "/.well-known/mcp-tools/" + GROUP + "/producer-" + label + "/compatible";
            for (int i = 0; i < WARMUP; i++) {
                call(root, path);
            }
            long[] nanos = new long[TIMED];
            int compatible = -1;
            for (int i = 0; i < TIMED; i++) {
                long t0 = System.nanoTime();
                Response response = call(root, path);
                nanos[i] = System.nanoTime() - t0;
                compatible = response.jsonPath().getInt("count");
            }
            Arrays.sort(nanos);
            w.println(String.format("  %-8s min %6.1f  median %6.1f  p95 %6.1f  max %6.1f ms   compatible=%d",
                    label, ms(nanos[0]), ms(nanos[TIMED / 2]), ms(nanos[(int) Math.ceil(TIMED * 0.95) - 1]),
                    ms(nanos[TIMED - 1]), compatible));
        }
        w.println();
        w.flush();
    }

    /**
     * Same JVM, same run, interleaved per iteration: the HTTP request, the endpoint's exact storage
     * sequence, the JSON parse of each candidate, and compare() over the parsed candidates.
     */
    private void decompose(PrintWriter w, String root, String[] labels, JsonNode[] producerDocs) throws Exception {
        w.println("== decomposition at the cap, same JVM and run, interleaved, " + TIMED + " timed after "
                + WARMUP + " warmup");
        for (int i = 0; i < labels.length; i++) {
            String source = "producer-" + labels[i];
            String path = "/.well-known/mcp-tools/" + GROUP + "/" + source + "/compatible";
            long[] http = new long[TIMED];
            long[] store = new long[TIMED];
            long[] parse = new long[TIMED];
            long[] compare = new long[TIMED];
            int scanned = 0;
            for (int it = -WARMUP; it < TIMED; it++) {
                long t0 = System.nanoTime();
                call(root, path);
                long t1 = System.nanoTime();
                List<String> contents = new ArrayList<>();
                scanStorage(source, contents);
                long t2 = System.nanoTime();
                List<JsonNode> roots = new ArrayList<>();
                for (String content : contents) {
                    roots.add(MAPPER.readTree(content));
                }
                long t3 = System.nanoTime();
                PreparedProducer prepared = crossTool.prepareProducer(producerDocs[i]);
                for (JsonNode candidate : roots) {
                    crossTool.compare(prepared, candidate);
                }
                long t4 = System.nanoTime();
                if (it >= 0) {
                    http[it] = t1 - t0;
                    store[it] = t2 - t1;
                    parse[it] = t3 - t2;
                    compare[it] = t4 - t3;
                }
                scanned = contents.size();
            }
            w.println(String.format("  %-8s candidates=%d  medians ms: request %.1f = storage %.1f + parse %.1f"
                    + " + compare %.1f + other %.1f", labels[i], scanned, med(http), med(store), med(parse),
                    med(compare), med(http) - med(store) - med(parse) - med(compare)));
            w.println(String.format("  %-8s p95 ms:     request %.1f   storage %.1f   parse %.1f   compare %.1f",
                    "", p95(http), p95(store), p95(parse), p95(compare)));
        }
        w.println();
        w.flush();
    }

    /** The storage calls findCompatibleCandidates makes: one search with its count, then two calls per candidate. */
    private void scanStorage(String sourceArtifactId, List<String> contentsOut) {
        Set<SearchFilter> filters = new HashSet<>();
        filters.add(SearchFilter.ofArtifactType(ArtifactType.MCP_TOOL));
        ArtifactSearchResultsDto results = storage.searchArtifacts(filters, OrderBy.createdOn,
                OrderDirection.desc, 0, 500, false);
        for (SearchedArtifactDto candidate : results.getArtifacts()) {
            if (sourceArtifactId.equals(candidate.getArtifactId()) && GROUP.equals(candidate.getGroupId())) {
                continue;
            }
            GA ga = new GA(candidate.getGroupId(), candidate.getArtifactId());
            GAV gav = VersionExpressionParser.parse(ga, "branch=latest",
                    (g, branchId) -> storage.getBranchTip(g, branchId, RetrievalBehavior.SKIP_DISABLED_LATEST));
            StoredArtifactVersionDto stored = storage.getArtifactVersionContent(gav.getRawGroupIdWithNull(),
                    gav.getRawArtifactId(), gav.getRawVersionId());
            contentsOut.add(stored.getContent().content());
        }
    }

    private static double med(long[] nanos) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        return ms(sorted[sorted.length / 2]);
    }

    private static double p95(long[] nanos) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        return ms(sorted[(int) Math.ceil(sorted.length * 0.95) - 1]);
    }

    private static Response call(String root, String path) {
        Response response = RestAssured.given().baseUri(root).when().get(path);
        response.then().statusCode(200);
        return response;
    }

    private void register(String artifactId, String content) throws Exception {
        CreateArtifact createArtifact = new CreateArtifact();
        createArtifact.setArtifactId(artifactId);
        createArtifact.setArtifactType(ArtifactType.MCP_TOOL);
        CreateVersion createVersion = new CreateVersion();
        VersionContent versionContent = new VersionContent();
        versionContent.setContent(content);
        versionContent.setContentType(ContentTypes.APPLICATION_JSON);
        createVersion.setContent(versionContent);
        createArtifact.setFirstVersion(createVersion);
        clientV3.groups().byGroupId(GROUP).artifacts().post(createArtifact);
    }

    private static double ms(long nanos) {
        return nanos / 1_000_000.0;
    }
}
