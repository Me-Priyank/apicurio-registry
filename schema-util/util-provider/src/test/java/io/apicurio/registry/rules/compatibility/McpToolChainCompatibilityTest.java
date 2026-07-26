package io.apicurio.registry.rules.compatibility;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link McpToolChainCompatibility} &mdash; can a producer tool's output feed a consumer
 * tool's input?
 */
class McpToolChainCompatibilityTest {

    @Test
    void testCompatibleWhenOutputSuppliesAllRequiredInputs() {
        String producer = """
                {
                    "name": "geocode",
                    "outputSchema": {
                        "type": "object",
                        "properties": {
                            "latitude": { "type": "number" },
                            "longitude": { "type": "number" }
                        }
                    }
                }
                """;
        String consumer = """
                {
                    "name": "weather_lookup",
                    "inputSchema": {
                        "type": "object",
                        "properties": {
                            "latitude": { "type": "number" },
                            "longitude": { "type": "number" }
                        },
                        "required": ["latitude", "longitude"]
                    }
                }
                """;

        McpToolChainCompatibilityResult result =
                McpToolChainCompatibility.analyze(producer, consumer);

        assertTrue(result.isCompatible(),
                "Producer output supplies both required inputs with matching types");
        assertEquals(2, result.getMatchedRequiredParameters().size());
        assertTrue(result.getMissingRequiredParameters().isEmpty());
        assertTrue(result.getTypeMismatches().isEmpty());
    }

    @Test
    void testIncompatibleWhenRequiredInputMissingFromOutput() {
        String producer = """
                {
                    "name": "geocode",
                    "outputSchema": {
                        "type": "object",
                        "properties": {
                            "latitude": { "type": "number" }
                        }
                    }
                }
                """;
        String consumer = """
                {
                    "name": "weather_lookup",
                    "inputSchema": {
                        "type": "object",
                        "properties": {
                            "latitude": { "type": "number" },
                            "longitude": { "type": "number" }
                        },
                        "required": ["latitude", "longitude"]
                    }
                }
                """;

        McpToolChainCompatibilityResult result =
                McpToolChainCompatibility.analyze(producer, consumer);

        assertFalse(result.isCompatible(), "Producer does not supply the required 'longitude'");
        assertEquals(1, result.getMissingRequiredParameters().size());
        assertEquals("longitude", result.getMissingRequiredParameters().get(0));
    }

    @Test
    void testIncompatibleWhenTypeDoesNotMatch() {
        String producer = """
                {
                    "name": "search",
                    "outputSchema": {
                        "type": "object",
                        "properties": {
                            "id": { "type": "string" }
                        }
                    }
                }
                """;
        String consumer = """
                {
                    "name": "fetch_record",
                    "inputSchema": {
                        "type": "object",
                        "properties": {
                            "id": { "type": "integer" }
                        },
                        "required": ["id"]
                    }
                }
                """;

        McpToolChainCompatibilityResult result =
                McpToolChainCompatibility.analyze(producer, consumer);

        assertFalse(result.isCompatible(), "Producer supplies 'id' as string, consumer needs integer");
        assertEquals(1, result.getTypeMismatches().size());
        assertEquals("id", result.getTypeMismatches().get(0).getParameter());
        assertEquals("integer", result.getTypeMismatches().get(0).getExpectedType());
        assertEquals("string", result.getTypeMismatches().get(0).getActualType());
    }

    @Test
    void testNumericWideningIntegerSatisfiesNumber() {
        String producer = """
                {
                    "name": "counter",
                    "outputSchema": {
                        "type": "object",
                        "properties": {
                            "count": { "type": "integer" }
                        }
                    }
                }
                """;
        String consumer = """
                {
                    "name": "scale",
                    "inputSchema": {
                        "type": "object",
                        "properties": {
                            "count": { "type": "number" }
                        },
                        "required": ["count"]
                    }
                }
                """;

        McpToolChainCompatibilityResult result =
                McpToolChainCompatibility.analyze(producer, consumer);

        assertTrue(result.isCompatible(), "An integer output is an acceptable number input");
    }

    @Test
    void testCompatibleWhenConsumerRequiresNothing() {
        String producer = """
                {
                    "name": "no_output_tool",
                    "inputSchema": { "type": "object" }
                }
                """;
        String consumer = """
                {
                    "name": "ping",
                    "inputSchema": {
                        "type": "object",
                        "properties": {
                            "verbose": { "type": "boolean" }
                        }
                    }
                }
                """;

        McpToolChainCompatibilityResult result =
                McpToolChainCompatibility.analyze(producer, consumer);

        assertTrue(result.isCompatible(),
                "A consumer with no required inputs can be driven by any producer");
    }

    @Test
    void testIncompatibleWhenProducerHasNoOutputSchemaButConsumerRequiresInput() {
        String producer = """
                {
                    "name": "logger",
                    "inputSchema": { "type": "object" }
                }
                """;
        String consumer = """
                {
                    "name": "fetch_record",
                    "inputSchema": {
                        "type": "object",
                        "properties": {
                            "id": { "type": "string" }
                        },
                        "required": ["id"]
                    }
                }
                """;

        McpToolChainCompatibilityResult result =
                McpToolChainCompatibility.analyze(producer, consumer);

        assertFalse(result.isCompatible(),
                "A producer that emits no structured output cannot satisfy a required input");
        assertEquals(1, result.getMissingRequiredParameters().size());
        assertEquals("id", result.getMissingRequiredParameters().get(0));
    }

    @Test
    void testMatchedOptionalParametersAreReportedButDoNotAffectVerdict() {
        String producer = """
                {
                    "name": "geocode",
                    "outputSchema": {
                        "type": "object",
                        "properties": {
                            "latitude": { "type": "number" },
                            "longitude": { "type": "number" },
                            "accuracy": { "type": "number" }
                        }
                    }
                }
                """;
        String consumer = """
                {
                    "name": "weather_lookup",
                    "inputSchema": {
                        "type": "object",
                        "properties": {
                            "latitude": { "type": "number" },
                            "longitude": { "type": "number" },
                            "accuracy": { "type": "number" }
                        },
                        "required": ["latitude", "longitude"]
                    }
                }
                """;

        McpToolChainCompatibilityResult result =
                McpToolChainCompatibility.analyze(producer, consumer);

        assertTrue(result.isCompatible());
        assertEquals(2, result.getMatchedRequiredParameters().size());
        assertEquals(1, result.getMatchedOptionalParameters().size());
        assertEquals("accuracy", result.getMatchedOptionalParameters().get(0));
    }

    @Test
    void testUnspecifiedTypesAreAcceptedConservatively() {
        String producer = """
                {
                    "name": "producer",
                    "outputSchema": {
                        "type": "object",
                        "properties": {
                            "payload": { "anyOf": [ { "type": "string" }, { "type": "object" } ] }
                        }
                    }
                }
                """;
        String consumer = """
                {
                    "name": "consumer",
                    "inputSchema": {
                        "type": "object",
                        "properties": {
                            "payload": {}
                        },
                        "required": ["payload"]
                    }
                }
                """;

        McpToolChainCompatibilityResult result =
                McpToolChainCompatibility.analyze(producer, consumer);

        assertTrue(result.isCompatible(),
                "When neither side declares a concrete scalar type, presence of the property suffices");
        assertEquals(1, result.getMatchedRequiredParameters().size());
    }

    @Test
    void testErrorWhenConsumerHasNoInputSchema() {
        String producer = """
                { "name": "producer", "outputSchema": { "type": "object" } }
                """;
        String consumer = """
                { "name": "consumer" }
                """;

        McpToolChainCompatibilityResult result =
                McpToolChainCompatibility.analyze(producer, consumer);

        assertFalse(result.isCompatible());
        assertNotNull(result.getError());
    }

    @Test
    void testErrorOnMalformedJson() {
        McpToolChainCompatibilityResult result =
                McpToolChainCompatibility.analyze("{ not json", "{ also not json");

        assertFalse(result.isCompatible());
        assertNotNull(result.getError());
    }
}
