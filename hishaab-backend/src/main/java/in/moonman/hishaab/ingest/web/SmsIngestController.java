package in.moonman.hishaab.ingest.web;

import in.moonman.hishaab.device.DevicePrincipal;
import in.moonman.hishaab.ingest.internal.SmsIngestService;
import in.moonman.hishaab.ingest.internal.SmsIngestService.IncomingSms;
import in.moonman.hishaab.ingest.internal.SmsIngestService.IngestResult;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * SPEC-011: {@code POST /api/v1/ingest/sms} (device auth). Items are deliberately not bean-validated here:
 * a bad item is reported in {@code rejected}, it does not fail the batch (R-011-7).
 */
@RestController
class SmsIngestController {

    private final SmsIngestService ingest;

    SmsIngestController(SmsIngestService ingest) {
        this.ingest = ingest;
    }

    @PostMapping("/api/v1/ingest/sms")
    IngestResult ingestSms(@AuthenticationPrincipal DevicePrincipal device, @RequestBody SmsBatch batch) {
        return ingest.ingest(device.deviceId(), batch.messages());
    }

    record SmsBatch(List<IncomingSms> messages) {
    }
}
