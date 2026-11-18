package in.moonman.hishaab.device.web;

import in.moonman.hishaab.device.DevicePrincipal;
import in.moonman.hishaab.device.DeviceService;
import in.moonman.hishaab.device.DeviceService.CreatedDevice;
import in.moonman.hishaab.device.DeviceService.DeviceView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * SPEC-010. Register/list/revoke need user auth; {@code PATCH /devices/me} needs device auth
 * (enforced in the security config).
 */
@RestController
@RequestMapping("/api/v1/devices")
class DeviceController {

    private final DeviceService devices;

    DeviceController(DeviceService devices) {
        this.devices = devices;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CreatedDevice register(@Valid @RequestBody RegisterDeviceRequest request) {
        return devices.register(request.name());
    }

    @GetMapping
    List<DeviceView> list() {
        return devices.list();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@PathVariable UUID id) {
        devices.revoke(id);
    }

    @PatchMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void updateMe(@AuthenticationPrincipal DevicePrincipal device, @Valid @RequestBody UpdateMeRequest request) {
        devices.updateFcmToken(device.deviceId(), request.fcmToken());
    }

    record RegisterDeviceRequest(@NotBlank @Size(max = 100) String name) {
    }

    record UpdateMeRequest(@NotBlank @Size(max = 4096) String fcmToken) {
    }
}
