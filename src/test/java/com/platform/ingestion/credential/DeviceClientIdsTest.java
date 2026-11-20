package com.platform.ingestion.credential;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DeviceClientIdsTest {

    @Test
    void usesOnlyCharactersKeycloakUsernamesAccept() {
        String id = DeviceClientIds.forDevice("tenant-alpha", "MACHINE_001:Line.2");

        assertThat(id).matches("^device-tenant-alpha-[a-z0-9.-]+-[0-9a-f]{16}$");
        assertThat(id.length()).isLessThanOrEqualTo(120);
    }

    @Test
    void deviceIdsDifferingOnlyByCaseOrPunctuationStayDistinct() {
        // Keycloak lower-cases the service-account username, so a readable-only rule would collide here.
        assertThat(DeviceClientIds.forDevice("tenant-alpha", "A_1"))
                .isNotEqualTo(DeviceClientIds.forDevice("tenant-alpha", "a-1"))
                .isNotEqualTo(DeviceClientIds.forDevice("tenant-alpha", "a:1"));
    }

    @Test
    void hyphenatedTenantAndDeviceCannotBeShiftedAcrossTheBoundary() {
        assertThat(DeviceClientIds.forDevice("tenant-a", "b-c"))
                .isNotEqualTo(DeviceClientIds.forDevice("tenant-a-b", "c"));
    }

    @Test
    void isDeterministic() {
        assertThat(DeviceClientIds.forDevice("tenant-alpha", "MACHINE-001"))
                .isEqualTo(DeviceClientIds.forDevice("tenant-alpha", "MACHINE-001"));
    }
}
