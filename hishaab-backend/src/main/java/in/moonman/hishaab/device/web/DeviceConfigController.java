package in.moonman.hishaab.device.web;

import in.moonman.hishaab.device.DevicePrincipal;
import in.moonman.hishaab.device.DeviceService;
import in.moonman.hishaab.settings.SettingsService;
import java.util.List;
import java.util.OptionalLong;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** SPEC-010 / AC-010-4: what the SMS worker needs to know (device auth). */
@RestController
class DeviceConfigController {

    private final DeviceService devices;
    private final SettingsService settings;

    DeviceConfigController(DeviceService devices, SettingsService settings) {
        this.devices = devices;
        this.settings = settings;
    }

    @GetMapping("/api/v1/device/config")
    DeviceConfig config(@AuthenticationPrincipal DevicePrincipal device) {
        OptionalLong cursor = devices.smsCursorMs(device.deviceId());
        return new DeviceConfig(settings.senderAllowlist(), settings.scanCron(), settings.scanZone().getId(),
                settings.backfillDays(), cursor.isPresent() ? cursor.getAsLong() : null);
    }

    /** {@code cursorMs} is null until the device's first successful upload. */
    record DeviceConfig(List<String> senderAllowlist, String scanCron, String scanZone, int backfillDays,
                        Long cursorMs) {
    }
}
