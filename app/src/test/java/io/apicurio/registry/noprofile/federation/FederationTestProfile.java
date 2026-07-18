package io.apicurio.registry.noprofile.federation;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Enables A2A + experimental features (so agent cards and the well-known search are available)
 * and disables dev-services, since the test uses the default in-memory H2 storage and needs no
 * external containers.
 */
public class FederationTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "apicurio.features.experimental.enabled", "true",
                "apicurio.a2a.enabled", "true",
                "quarkus.devservices.enabled", "false",
                "quarkus.kubernetes-client.devservices.enabled", "false"
        );
    }
}
