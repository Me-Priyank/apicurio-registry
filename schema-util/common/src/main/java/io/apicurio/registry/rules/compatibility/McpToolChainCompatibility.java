package io.apicurio.registry.rules.compatibility;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apicurio.registry.rules.compatibility.McpToolChainCompatibilityResult.TypeMismatch;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Analyzes whether the structured output of one MCP tool (the "producer") can be fed as the
 * structured input of another MCP tool (the "consumer") &mdash; that is, whether the two tools can
 * be <em>chained</em> {@code producer.outputSchema -> consumer.inputSchema}.
 *
 * <p>This is a different question from the one {@link McpToolCompatibilityChecker} answers. That
 * checker verifies the <em>evolution</em> of a single tool across versions (is v2 backward
 * compatible with v1?). This analyzer compares two <em>different</em> tools to decide whether one
 * can drive the other, which is the basis for a "Compatible Tools" discovery capability.</p>
 *
 * <p>The rule is intentionally conservative and easy to reason about:</p>
 * <ul>
 *   <li>For every property the consumer marks as {@code required}, the producer's
 *       {@code outputSchema} must expose a property of the same name.</li>
 *   <li>When both sides declare a concrete scalar {@code type} for that property, the producer's
 *       type must be assignable to the type the consumer expects (equal types, or the numeric
 *       widening {@code integer -> number}).</li>
 *   <li>Optional consumer properties never make a pair incompatible; when the producer happens to
 *       supply them they are reported so callers can see how completely the tools line up.</li>
 * </ul>
 *
 * <p>MCP tool definitions describe their input and output using JSON-Schema-style objects (the
 * {@code inputSchema} and {@code outputSchema} fields of a tool). This analyzer works structurally
 * on those objects. It reports what it can prove and treats anything it cannot resolve as a
 * mismatch rather than silently assuming success.</p>
 *
 * @see <a href="https://modelcontextprotocol.io/specification/2025-11-25/server/tools">MCP Tools</a>
 * @see McpToolChainCompatibilityResult
 */
public final class McpToolChainCompatibility {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpToolChainCompatibility() {
    }

    /**
     * Analyze chaining compatibility from raw MCP tool definition JSON documents.
     *
     * @param producerToolJson the MCP tool whose output would be produced
     * @param consumerToolJson the MCP tool whose input would be consumed
     * @return the analysis result, never {@code null}
     */
    public static McpToolChainCompatibilityResult analyze(String producerToolJson,
            String consumerToolJson) {
        try {
            JsonNode producer = MAPPER.readTree(producerToolJson);
            JsonNode consumer = MAPPER.readTree(consumerToolJson);
            return analyze(producer, consumer);
        } catch (Exception e) {
            return McpToolChainCompatibilityResult
                    .error("Failed to parse MCP tool definition: " + e.getMessage());
        }
    }

    /**
     * Analyze chaining compatibility from already-parsed MCP tool definitions.
     *
     * @param producerTool the parsed MCP tool whose output would be produced
     * @param consumerTool the parsed MCP tool whose input would be consumed
     * @return the analysis result, never {@code null}
     */
    public static McpToolChainCompatibilityResult analyze(JsonNode producerTool,
            JsonNode consumerTool) {
        JsonNode inputSchema = consumerTool == null ? null : consumerTool.get("inputSchema");
        if (inputSchema == null || !inputSchema.isObject()) {
            return McpToolChainCompatibilityResult
                    .error("Consumer tool has no object 'inputSchema'");
        }

        JsonNode outputSchema = producerTool == null ? null : producerTool.get("outputSchema");

        Set<String> required = requiredProperties(inputSchema);
        JsonNode consumedProps = objectField(inputSchema, "properties");
        JsonNode producedProps = objectField(outputSchema, "properties");

        List<String> matchedRequired = new ArrayList<>();
        List<String> matchedOptional = new ArrayList<>();
        List<String> missingRequired = new ArrayList<>();
        List<TypeMismatch> typeMismatches = new ArrayList<>();

        // Required parameters drive the compatibility verdict.
        for (String param : required) {
            if (producedProps == null || !producedProps.has(param)) {
                missingRequired.add(param);
                continue;
            }
            String expectedType = declaredType(consumedProps, param);
            String actualType = declaredType(producedProps, param);
            if (typeAssignable(actualType, expectedType)) {
                matchedRequired.add(param);
            } else {
                typeMismatches.add(new TypeMismatch(param, expectedType, actualType));
            }
        }

        // Optional parameters the producer also happens to supply (informational only).
        if (producedProps != null && consumedProps != null) {
            Iterator<String> consumed = consumedProps.fieldNames();
            while (consumed.hasNext()) {
                String param = consumed.next();
                if (!required.contains(param) && producedProps.has(param)) {
                    matchedOptional.add(param);
                }
            }
        }

        return McpToolChainCompatibilityResult.of(matchedRequired, matchedOptional, missingRequired,
                typeMismatches);
    }

    /**
     * Decide whether a producer's JSON type can satisfy the consumer's expected JSON type. When
     * either side leaves the type unspecified we do not have enough information to reject the pair,
     * so we accept it (the required property name is present, which is the primary signal).
     */
    private static boolean typeAssignable(String producedType, String expectedType) {
        if (expectedType == null || producedType == null) {
            return true;
        }
        if (expectedType.equals(producedType)) {
            return true;
        }
        // Numeric widening: an integer value is a valid number.
        return "number".equals(expectedType) && "integer".equals(producedType);
    }

    private static Set<String> requiredProperties(JsonNode inputSchema) {
        Set<String> result = new LinkedHashSet<>();
        JsonNode requiredNode = inputSchema.get("required");
        if (requiredNode != null && requiredNode.isArray()) {
            for (JsonNode item : requiredNode) {
                if (item.isTextual()) {
                    result.add(item.asText());
                }
            }
        }
        return result;
    }

    private static JsonNode objectField(JsonNode parent, String field) {
        if (parent == null) {
            return null;
        }
        JsonNode node = parent.get(field);
        return node != null && node.isObject() ? node : null;
    }

    /**
     * Return the declared scalar {@code type} of a named property, or {@code null} when the
     * property or its type is absent or not a simple string (e.g. composite {@code anyOf} schemas).
     */
    private static String declaredType(JsonNode properties, String property) {
        if (properties == null) {
            return null;
        }
        JsonNode prop = properties.get(property);
        if (prop == null || !prop.isObject()) {
            return null;
        }
        JsonNode type = prop.get("type");
        return type != null && type.isTextual() ? type.asText() : null;
    }
}
