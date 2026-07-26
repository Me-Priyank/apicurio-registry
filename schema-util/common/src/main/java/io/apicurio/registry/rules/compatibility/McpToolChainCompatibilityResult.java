package io.apicurio.registry.rules.compatibility;

import java.util.List;
import java.util.Objects;

/**
 * The outcome of a chaining-compatibility analysis between two MCP tools: whether a "producer"
 * tool's structured output can be fed as the structured input of a "consumer" tool.
 *
 * This is deliberately more descriptive than a boolean. Beyond {@link #isCompatible()} it explains
 * <em>why</em>: which required consumer parameters the producer satisfies, which it fails to
 * supply, and which are supplied with an incompatible type. That detail is what a discovery API or
 * a UI needs in order to show an actionable "these tools chain, those don't (because ...)" view.
 *
 * @see McpToolChainCompatibility
 */
public final class McpToolChainCompatibilityResult {

    private final boolean compatible;
    private final List<String> matchedRequiredParameters;
    private final List<String> matchedOptionalParameters;
    private final List<String> missingRequiredParameters;
    private final List<TypeMismatch> typeMismatches;
    private final String error;

    private McpToolChainCompatibilityResult(boolean compatible,
            List<String> matchedRequiredParameters, List<String> matchedOptionalParameters,
            List<String> missingRequiredParameters, List<TypeMismatch> typeMismatches,
            String error) {
        this.compatible = compatible;
        this.matchedRequiredParameters = List.copyOf(matchedRequiredParameters);
        this.matchedOptionalParameters = List.copyOf(matchedOptionalParameters);
        this.missingRequiredParameters = List.copyOf(missingRequiredParameters);
        this.typeMismatches = List.copyOf(typeMismatches);
        this.error = error;
    }

    static McpToolChainCompatibilityResult of(List<String> matchedRequired,
            List<String> matchedOptional, List<String> missingRequired,
            List<TypeMismatch> typeMismatches) {
        boolean compatible = missingRequired.isEmpty() && typeMismatches.isEmpty();
        return new McpToolChainCompatibilityResult(compatible, matchedRequired, matchedOptional,
                missingRequired, typeMismatches, null);
    }

    static McpToolChainCompatibilityResult error(String message) {
        return new McpToolChainCompatibilityResult(false, List.of(), List.of(), List.of(),
                List.of(), message);
    }

    /**
     * @return true when the producer's output satisfies every required parameter of the consumer's
     *         input, each with a compatible type.
     */
    public boolean isCompatible() {
        return compatible;
    }

    /**
     * @return required consumer parameters that the producer supplies with a compatible type.
     */
    public List<String> getMatchedRequiredParameters() {
        return matchedRequiredParameters;
    }

    /**
     * @return optional consumer parameters that the producer also happens to supply. These never
     *         affect compatibility; they indicate how completely the producer feeds the consumer.
     */
    public List<String> getMatchedOptionalParameters() {
        return matchedOptionalParameters;
    }

    /**
     * @return required consumer parameters that the producer does not supply at all.
     */
    public List<String> getMissingRequiredParameters() {
        return missingRequiredParameters;
    }

    /**
     * @return required consumer parameters the producer supplies, but with an incompatible type.
     */
    public List<TypeMismatch> getTypeMismatches() {
        return typeMismatches;
    }

    /**
     * @return a structural/parse error message, or {@code null} when the analysis ran normally.
     *         When non-null, {@link #isCompatible()} is always {@code false}.
     */
    public String getError() {
        return error;
    }

    /**
     * A required parameter that the producer supplies under the same name but with a JSON type the
     * consumer cannot accept.
     */
    public static final class TypeMismatch {

        private final String parameter;
        private final String expectedType;
        private final String actualType;

        public TypeMismatch(String parameter, String expectedType, String actualType) {
            this.parameter = parameter;
            this.expectedType = expectedType;
            this.actualType = actualType;
        }

        public String getParameter() {
            return parameter;
        }

        public String getExpectedType() {
            return expectedType;
        }

        public String getActualType() {
            return actualType;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            TypeMismatch that = (TypeMismatch) o;
            return Objects.equals(parameter, that.parameter)
                    && Objects.equals(expectedType, that.expectedType)
                    && Objects.equals(actualType, that.actualType);
        }

        @Override
        public int hashCode() {
            return Objects.hash(parameter, expectedType, actualType);
        }

        @Override
        public String toString() {
            return "TypeMismatch{parameter='" + parameter + "', expectedType='" + expectedType
                    + "', actualType='" + actualType + "'}";
        }
    }
}
