package com.drivesync.server.dto;

import jakarta.validation.constraints.NotBlank;

/** Manual override: which device's version should keep the original filename going forward. */
public record ConflictResolutionRequest(
        @NotBlank String keepDeviceId
) {
}
