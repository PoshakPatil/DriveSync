package com.drivesync.server.dto;

import jakarta.validation.constraints.NotBlank;

public record DeviceRegistrationRequest(
        @NotBlank String deviceId,
        String displayName
) {
}
