package in.moonman.hishaab.device;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/** Public API of the device module (SPEC-010). */
public interface DeviceService {

    /** Registers a device. The returned token is shown once; only its hash is stored (R-010-1). */
    CreatedDevice register(String name);

    List<DeviceView> list();

    /** Revokes a device; idempotent. Unknown id: ApiException 404 {@code DEVICE_NOT_FOUND}. */
    void revoke(UUID deviceId);

    /** Resolves a raw {@code hdt_...} token to its device, if the token is known and not revoked. */
    Optional<DevicePrincipal> authenticate(String rawToken);

    void updateFcmToken(UUID deviceId, String fcmToken);

    /** Highest SMS {@code receivedAtMs} the device has uploaded, if any. */
    OptionalLong smsCursorMs(UUID deviceId);

    /**
     * Records an SMS upload (R-011-6): sets {@code last_sync_at = now()} and raises the cursor to
     * {@code batchMaxReceivedAtMs} if that is higher. The cursor never moves backwards.
     *
     * @return the device's cursor after the update (empty if it has never uploaded a storable message)
     */
    OptionalLong recordSmsSync(UUID deviceId, OptionalLong batchMaxReceivedAtMs);

    record CreatedDevice(UUID deviceId, String deviceToken) {
    }

    record DeviceView(UUID id, String name, Instant createdAt, Instant lastSyncAt, Instant revokedAt,
                      boolean pushEnabled) {
    }
}
