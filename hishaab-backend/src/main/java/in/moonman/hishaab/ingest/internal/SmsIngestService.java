package in.moonman.hishaab.ingest.internal;

import in.moonman.hishaab.device.DeviceService;
import in.moonman.hishaab.ingest.RawMessageReceived;
import in.moonman.hishaab.ingest.SmsId;
import in.moonman.hishaab.settings.SettingsService;
import in.moonman.hishaab.shared.ApiException;
import in.moonman.hishaab.shared.SenderIds;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SPEC-011: stores raw bank SMS idempotently. One bad item never fails the batch (R-011-7); the inserts, the
 * device cursor and the events all belong to one transaction, so the cursor only advances if the rows commit.
 */
@Service
public class SmsIngestService {

    static final int MAX_BATCH = 500;
    static final int MAX_BODY_CHARS = 10_000;
    static final int MAX_SENDER_CHARS = 64;

    private static final Logger log = LoggerFactory.getLogger(SmsIngestService.class);
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    private final JdbcTemplate jdbc;
    private final SettingsService settings;
    private final DeviceService devices;
    private final ApplicationEventPublisher events;

    SmsIngestService(JdbcTemplate jdbc, SettingsService settings, DeviceService devices,
                     ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.devices = devices;
        this.events = events;
    }

    public record IncomingSms(String id, String sender, String body, Long receivedAtMs) {
    }

    public record Rejection(String id, String reason) {
    }

    public record IngestResult(List<String> accepted, List<String> duplicate, List<Rejection> rejected,
                               Long cursorMs) {
    }

    @Transactional
    public IngestResult ingest(UUID deviceId, List<IncomingSms> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_BATCH", "messages must contain 1 to 500 items");
        }
        if (messages.size() > MAX_BATCH) { // R-011-1
            throw new ApiException(HttpStatus.BAD_REQUEST, "BATCH_TOO_LARGE",
                    "messages has " + messages.size() + " items; the maximum is " + MAX_BATCH);
        }
        Set<String> allowlist = new HashSet<>(settings.senderAllowlist());

        List<String> accepted = new ArrayList<>();
        List<String> duplicate = new ArrayList<>();
        List<Rejection> rejected = new ArrayList<>();
        long maxSeenMs = Long.MIN_VALUE;

        for (IncomingSms sms : messages) {
            String reason = rejectionReason(sms, allowlist);
            if (reason != null) {
                rejected.add(new Rejection(sms == null ? null : sms.id(), reason));
                continue;
            }
            if (insert(sms, deviceId)) {
                accepted.add(sms.id());
                events.publishEvent(new RawMessageReceived(sms.id())); // listeners run after commit (R-011-5)
            } else {
                duplicate.add(sms.id()); // R-011-4
            }
            maxSeenMs = Math.max(maxSeenMs, sms.receivedAtMs());
        }

        // R-011-6: cursor = max receivedAtMs over accepted ∪ duplicate; never moves backwards.
        OptionalLong cursor = devices.recordSmsSync(deviceId,
                maxSeenMs == Long.MIN_VALUE ? OptionalLong.empty() : OptionalLong.of(maxSeenMs));

        log.info("event=sms_ingested deviceId={} accepted={} duplicate={} rejected={}",
                deviceId, accepted.size(), duplicate.size(), rejected.size());
        return new IngestResult(accepted, duplicate, rejected, cursor.isPresent() ? cursor.getAsLong() : null);
    }

    /** Returns null if the item may be stored, else the rejection reason. Order: shape → hash → allowlist. */
    private static String rejectionReason(IncomingSms sms, Set<String> allowlist) {
        if (sms == null || sms.id() == null || sms.sender() == null || sms.body() == null
                || sms.receivedAtMs() == null || sms.receivedAtMs() <= 0
                || !SHA256_HEX.matcher(sms.id()).matches()
                || sms.sender().isEmpty() || sms.sender().length() > MAX_SENDER_CHARS
                || sms.body().length() > MAX_BODY_CHARS
                || sms.sender().indexOf('\0') >= 0 || sms.body().indexOf('\0') >= 0) { // Postgres TEXT rejects NUL
            return "INVALID_MESSAGE";
        }
        if (!SmsId.of(sms.sender(), sms.body(), sms.receivedAtMs()).equals(sms.id())) { // R-011-2
            return "HASH_MISMATCH";
        }
        boolean allowed = SenderIds.middlePart(sms.sender()).map(allowlist::contains).orElse(false);
        if (!allowed) { // R-011-3
            return "SENDER_NOT_ALLOWED";
        }
        return null;
    }

    /** @return true if a new row was stored, false if the ID already existed */
    private boolean insert(IncomingSms sms, UUID deviceId) {
        int rows = jdbc.update("""
                        INSERT INTO raw_message (id, source, sender, body, received_at, status, device_id)
                        VALUES (?, 'SMS', ?, ?, ?, 'NEW', ?)
                        ON CONFLICT (id) DO NOTHING""",
                sms.id(), sms.sender(), sms.body(),
                OffsetDateTime.ofInstant(Instant.ofEpochMilli(sms.receivedAtMs()), ZoneOffset.UTC),
                deviceId);
        return rows == 1;
    }
}
