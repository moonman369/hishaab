package in.moonman.hishaab.device.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "device")
class Device {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "fcm_token")
    private String fcmToken;

    @Column(name = "sms_cursor_ms")
    private Long smsCursorMs;

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Device() {
    }

    Device(UUID id, String name, String tokenHash, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
    }

    UUID id() {
        return id;
    }

    String name() {
        return name;
    }

    String fcmToken() {
        return fcmToken;
    }

    Long smsCursorMs() {
        return smsCursorMs;
    }

    Instant lastSyncAt() {
        return lastSyncAt;
    }

    Instant revokedAt() {
        return revokedAt;
    }

    Instant createdAt() {
        return createdAt;
    }

    void revoke(Instant at) {
        if (revokedAt == null) {
            revokedAt = at;
        }
    }

    void updateFcmToken(String token) {
        this.fcmToken = token;
    }
}
