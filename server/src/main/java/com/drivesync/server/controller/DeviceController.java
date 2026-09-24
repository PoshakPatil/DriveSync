package com.drivesync.server.controller;

import com.drivesync.server.dto.DeviceRegistrationRequest;
import com.drivesync.server.dto.DeviceResponse;
import com.drivesync.server.model.Device;
import com.drivesync.server.service.DeviceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @PostMapping("/register")
    public DeviceResponse register(@Valid @RequestBody DeviceRegistrationRequest request) {
        Device device = deviceService.registerOrTouch(request.deviceId(), request.displayName());
        return DeviceResponse.from(device);
    }

    @GetMapping
    public List<DeviceResponse> listAll() {
        return deviceService.listAll().stream().map(DeviceResponse::from).toList();
    }
}
