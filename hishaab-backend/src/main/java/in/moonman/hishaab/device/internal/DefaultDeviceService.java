package in.moonman.hishaab.device.internal;

import in.moonman.hishaab.device.DevicePrincipal;
import in.moonman.hishaab.device.DeviceService;
import in.moonman.hishaab.shared.ApiException;
import in.moonman.hishaab.shared.Ids;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DefaultDeviceService implements DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DefaultDeviceService.class);

    private final DeviceRepository devices;
    private final JdbcTemplate jdbc;
    private final Clock clock = Clock.systemUTC();

    DefaultDeviceService(DeviceRepository devices, JdbcTemplate jdbc) {
        this.devices = devices;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public CreatedDevice register(String name) {
        String token = DeviceTokens.newToken();
        Device device = devices.save(new Device(Ids.newId(), name.strip(), DeviceTokens.hash(token), clock.instant()));
        log.info("event=device_registered deviceId={}", device.id());
        return new CreatedDevice(device.id(), token);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeviceView> list() {
        return devices.findAllByOrderByCreatedAtAsc().stream()
                .map(d -> new DeviceView(d.id(), d.name(), d.createdAt(), d.lastSyncAt(), d.revokedAt(),
                        d.fcmToken() != null))
                .toList();
    }

    @Override
    @Transactional
    public void revoke(UUID deviceId) {
        find(deviceId).revoke(clock.instant());
        log.info("event=device_revoked deviceId={}", deviceId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DevicePrincipal> authenticate(String rawToken) {
        if (rawToken == null || !rawToken.startsWith(DeviceTokens.PREFIX)) {
            return Optional.empty();
        }
        return devices.findByTokenHashAndRevokedAtIsNull(DeviceTokens.hash(rawToken))
                .map(d -> new DevicePrincipal(d.id()));
    }

    @Override
    @Transactional
    public void updateFcmToken(UUID deviceId, String fcmToken) {
        find(deviceId).updateFcmToken(fcmToken.strip());
    }

    @Override
    @Transactional(readOnly = true)
    public OptionalLong smsCursorMs(UUID deviceId) {
        Long cursor = find(deviceId).smsCursorMs();
        return cursor == null ? OptionalLong.empty() : OptionalLong.of(cursor);
    }

    /** Single atomic UPDATE so concurrent uploads from one device cannot move the cursor backwards. */
    @Override
    @Transactional
    public OptionalLong recordSmsSync(UUID deviceId, OptionalLong batchMaxReceivedAtMs) {
        List<Long> cursor = batchMaxReceivedAtMs.isPresent()
                ? jdbc.queryForList("""
                        UPDATE device SET sms_cursor_ms = GREATEST(COALESCE(sms_cursor_ms, ?), ?), last_sync_at = now()
                        WHERE id = ? RETURNING sms_cursor_ms""", Long.class,
                        batchMaxReceivedAtMs.getAsLong(), batchMaxReceivedAtMs.getAsLong(), deviceId)
                : jdbc.queryForList("UPDATE device SET last_sync_at = now() WHERE id = ? RETURNING sms_cursor_ms",
                        Long.class, deviceId);
        if (cursor.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", "device " + deviceId + " not found");
        }
        Long value = cursor.getFirst();
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }

    private Device find(UUID deviceId) {
        return devices.findById(deviceId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", "device " + deviceId + " not found"));
    }
}
