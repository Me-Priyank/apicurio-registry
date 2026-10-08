package io.apicurio.registry.agents.mcptools.compatibility;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.apitomy.datamodels.jsonschema.compat.CompatibilityCheckResult;
import io.apitomy.datamodels.jsonschema.compat.JsonSchemaCompatibilityChecker;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Temporary cost measurement for #10427 / #8427. Not a real test.
 *
 * Producers: each of the 125 GitHub MCP server tools, with its own inputSchema as outputSchema
 * (no public tool in the sample declares an outputSchema). Candidates: 500, the sample cycled.
 *
 * Modes, per request (one producer against 500 candidates):
 *   today  prepareProducer once, then compare() per candidate (the shipped service)
 *   full   both schemas compared in full with $schema 2020-12, plus the closed producer run
 *   parse  readTree of the 500 stored candidate documents (done by the endpoint in both modes)
 */
class CompatibilityCostBench {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DRAFT_2020_12 = "https://json-schema.org/draft/2020-12/schema";
    private static final int CANDIDATES = 500;
    private static final int TIMED_PASSES = 3;

    private final CrossToolCompatibilityService service = new CrossToolCompatibilityService();
    private final JsonSchemaCompatibilityChecker checker = JsonSchemaCompatibilityChecker.builder().build();

    @Test
    void bench() throws Exception {
        Path dir = Path.of(System.getProperty("cost.snaps"));
        Path out = Path.of(System.getProperty("cost.out"));

        List<JsonNode> tools = new ArrayList<>();
        try (var files = Files.list(dir)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".snap")).sorted().toList()) {
                tools.add(MAPPER.readTree(Files.readString(p)));
            }
        }
        List<JsonNode> producers = new ArrayList<>();
        for (JsonNode tool : tools) {
            ObjectNode producer = tool.deepCopy();
            producer.set("outputSchema", tool.get("inputSchema").deepCopy());
            producers.add(producer);
        }
        List<JsonNode> candidates = new ArrayList<>();
        List<String> stored = new ArrayList<>();
        for (int i = 0; i < CANDIDATES; i++) {
            JsonNode tool = tools.get(i % tools.size());
            candidates.add(tool);
            stored.add(tool.toString());
        }

        int n = producers.size();
        long[][] today = new long[TIMED_PASSES][n];
        long[][] engine = new long[TIMED_PASSES][n];
        long[][] full = new long[TIMED_PASSES][n];
        long[] parse = new long[TIMED_PASSES * n];

        // warmup pass, discarded
        for (int p = 0; p < n; p++) {
            runToday(producers.get(p), candidates);
            runTodayEngine(producers.get(p), candidates);
            runFull(producers.get(p), candidates);
            runParse(stored);
        }
        for (int pass = 0; pass < TIMED_PASSES; pass++) {
            for (int p = 0; p < n; p++) {
                today[pass][p] = runToday(producers.get(p), candidates);
                engine[pass][p] = runTodayEngine(producers.get(p), candidates);
                full[pass][p] = runFull(producers.get(p), candidates);
                parse[pass * n + p] = runParse(stored);
            }
        }

        long[] todayMed = medianAcrossPasses(today);
        long[] engineMed = medianAcrossPasses(engine);
        long[] fullMed = medianAcrossPasses(full);

        // verdict data over the unique 125 x 125 pairs
        Map<String, Integer> verdicts = new TreeMap<>();
        Map<String, Integer> limitationCodes = new TreeMap<>();
        Map<String, Integer> unsupportedKeywords = new TreeMap<>();
        int fullNoDiff = 0;
        int fullUnsupported = 0;
        int fullThrew = 0;
        for (JsonNode producer : producers) {
            PreparedProducer prepared = service.prepareProducer(producer);
            String[] producerRuns = producerRuns(producer);
            for (JsonNode consumer : tools) {
                PairCompatibility result = service.compare(prepared, consumer);
                verdicts.merge(result.verdict().name(), 1, Integer::sum);
                for (CompatibilityLimitation limitation : result.limitations()) {
                    limitationCodes.merge(limitation.code().name(), 1, Integer::sum);
                    if (limitation.code() == LimitationCode.UNSUPPORTED_KEYWORD) {
                        String pointer = limitation.pointer();
                        unsupportedKeywords.merge(pointer.substring(pointer.lastIndexOf('/') + 1), 1,
                                Integer::sum);
                    }
                }
                try {
                    CompatibilityCheckResult r = checker.checkBackward(producerRuns[0], fullConsumer(consumer));
                    if (r.hasUnsupportedFeatures()) {
                        fullUnsupported++;
                    }
                    if (r.getIncompatibleDifferences().isEmpty()) {
                        fullNoDiff++;
                    }
                } catch (RuntimeException e) {
                    fullThrew++;
                }
            }
        }

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            w.println("java.version=" + System.getProperty("java.version"));
            w.println("availableProcessors=" + Runtime.getRuntime().availableProcessors());
            w.println("maxMemoryMB=" + Runtime.getRuntime().maxMemory() / (1024 * 1024));
            w.println("tools=" + tools.size() + " producers=" + n + " candidatesPerRequest=" + CANDIDATES
                    + " timedPasses=" + TIMED_PASSES + " (median of passes per producer)");
            w.println();
            w.println("PER REQUEST, one producer against " + CANDIDATES + " candidates, ms (distribution over "
                    + n + " producers):");
            w.println("  today   " + summary(todayMed) + "   (whole compare(): projection, readability, realignment,"
                    + " two checks, attribution)");
            w.println("  engine  " + summary(engineMed) + "   (projection plus the two checks only)");
            w.println("  full    " + summary(fullMed) + "   (full schemas, $schema 2020-12, two checks only)");
            w.println("  parse   " + summary(parse) + "   (readTree of the " + CANDIDATES
                    + " stored candidate documents, same in every mode)");
            w.println();
            w.println("PER COMPARISON (median request / " + CANDIDATES + "), us:");
            w.println("  today   " + perComparison(todayMed));
            w.println("  engine  " + perComparison(engineMed));
            w.println("  full    " + perComparison(fullMed));
            w.println();
            w.println("NAMED PRODUCERS (median of passes), ms:");
            for (String name : List.of("get_me", "search_orgs", "projects_write")) {
                for (int p = 0; p < n; p++) {
                    if (name.equals(producers.get(p).get("name").asText())) {
                        w.println(String.format("  %-15s today %6.1f  engine %6.1f  full %6.1f", name,
                                ms(todayMed[p]), ms(engineMed[p]), ms(fullMed[p])));
                    }
                }
            }
            w.println();
            w.println("full mode closed run applies to " + countClosable(producers) + " of " + n + " producers");
            w.println();
            w.println("VERDICTS today over " + (n * tools.size()) + " unique pairs: " + verdicts);
            w.println("LIMITATION CODES today (count of limitations): " + limitationCodes);
            w.println("UNSUPPORTED_KEYWORD by keyword: " + unsupportedKeywords);
            w.println();
            w.println("FULL comparison over " + (n * tools.size()) + " unique pairs (as written run): threw="
                    + fullThrew + " unsupportedFeatures=" + fullUnsupported + " noIncompatibleDifference="
                    + fullNoDiff);
        }
    }

    private long runToday(JsonNode producer, List<JsonNode> candidates) {
        long t0 = System.nanoTime();
        PreparedProducer prepared = service.prepareProducer(producer);
        for (JsonNode candidate : candidates) {
            service.compare(prepared, candidate);
        }
        return System.nanoTime() - t0;
    }

    /** Today's projection and the two engine checks, without readability, realignment or attribution. */
    private long runTodayEngine(JsonNode producer, List<JsonNode> candidates) {
        long t0 = System.nanoTime();
        SchemaProjection projection = SchemaProjector.project(producer.get("outputSchema"), "/outputSchema",
                SchemaSide.PRODUCER);
        String asWritten = projection.projected().toString();
        String closed = projection.closable() ? projection.closed().toString() : null;
        for (JsonNode candidate : candidates) {
            SchemaProjection consumer = SchemaProjector.project(candidate.get("inputSchema"), "/inputSchema",
                    SchemaSide.CONSUMER);
            String consumerText = consumer.projected().toString();
            try {
                checker.checkBackward(asWritten, consumerText);
                if (closed != null) {
                    checker.checkBackward(closed, consumerText);
                }
            } catch (RuntimeException e) {
                // the shipped service turns this into COMPARISON_FAILED
            }
        }
        return System.nanoTime() - t0;
    }

    private long runFull(JsonNode producer, List<JsonNode> candidates) {
        long t0 = System.nanoTime();
        String[] runs = producerRuns(producer);
        for (JsonNode candidate : candidates) {
            String consumer = fullConsumer(candidate);
            try {
                checker.checkBackward(runs[0], consumer);
                if (runs[1] != null) {
                    checker.checkBackward(runs[1], consumer);
                }
            } catch (RuntimeException e) {
                // counted in the verdict pass
            }
        }
        return System.nanoTime() - t0;
    }

    private long runParse(List<String> stored) throws Exception {
        long t0 = System.nanoTime();
        for (String document : stored) {
            MAPPER.readTree(document);
        }
        return System.nanoTime() - t0;
    }

    /** The producer as written, and closed when any of its objects is open. Built once per request. */
    private static String[] producerRuns(JsonNode producer) {
        ObjectNode asWritten = (ObjectNode) producer.get("outputSchema").deepCopy();
        asWritten.put("$schema", DRAFT_2020_12);
        ObjectNode closed = asWritten.deepCopy();
        boolean changed = close(closed);
        return new String[] { asWritten.toString(), changed ? closed.toString() : null };
    }

    private static String fullConsumer(JsonNode tool) {
        ObjectNode schema = (ObjectNode) tool.get("inputSchema").deepCopy();
        schema.put("$schema", DRAFT_2020_12);
        return schema.toString();
    }

    private static int countClosable(List<JsonNode> producers) {
        int closable = 0;
        for (JsonNode producer : producers) {
            if (close(producer.get("outputSchema").deepCopy())) {
                closable++;
            }
        }
        return closable;
    }

    /** Closes every object that declares properties but no additionalProperties, at any depth. */
    private static boolean close(JsonNode node) {
        if (!node.isObject()) {
            return false;
        }
        ObjectNode object = (ObjectNode) node;
        boolean changed = false;
        JsonNode properties = object.get("properties");
        if (properties != null && properties.isObject() && !properties.isEmpty()
                && !object.has("additionalProperties")) {
            object.set("additionalProperties", BooleanNode.FALSE);
            changed = true;
        }
        if (properties != null) {
            for (JsonNode child : properties) {
                changed |= close(child);
            }
        }
        for (String keyword : List.of("items", "additionalProperties")) {
            JsonNode child = object.get(keyword);
            if (child != null && child.isObject()) {
                changed |= close(child);
            }
        }
        for (String keyword : List.of("anyOf", "oneOf", "allOf", "prefixItems")) {
            JsonNode list = object.get(keyword);
            if (list != null && list.isArray()) {
                for (JsonNode child : list) {
                    changed |= close(child);
                }
            }
        }
        return changed;
    }

    private static long[] medianAcrossPasses(long[][] passes) {
        int n = passes[0].length;
        long[] median = new long[n];
        for (int p = 0; p < n; p++) {
            long[] values = new long[passes.length];
            for (int pass = 0; pass < passes.length; pass++) {
                values[pass] = passes[pass][p];
            }
            Arrays.sort(values);
            median[p] = values[values.length / 2];
        }
        return median;
    }

    private static String summary(long[] nanos) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        return String.format("min %.1f  median %.1f  p95 %.1f  max %.1f", ms(sorted[0]),
                ms(sorted[sorted.length / 2]), ms(sorted[(int) Math.ceil(sorted.length * 0.95) - 1]),
                ms(sorted[sorted.length - 1]));
    }

    private static String perComparison(long[] nanos) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        return String.format("%.0f", sorted[sorted.length / 2] / 1000.0 / CANDIDATES);
    }

    private static double ms(long nanos) {
        return nanos / 1_000_000.0;
    }
}
