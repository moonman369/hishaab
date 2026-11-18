package in.moonman.hishaab.parse.internal;

import in.moonman.hishaab.parse.ParseService;
import in.moonman.hishaab.parse.ParsedFields;
import in.moonman.hishaab.parse.ParsedMessage;
import in.moonman.hishaab.shared.Channel;
import in.moonman.hishaab.shared.Direction;
import in.moonman.hishaab.shared.SenderIds;
import in.moonman.hishaab.shared.Source;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * SPEC-013 template engine: candidates by source + sender (R-013-2), first template whose regex is found and whose
 * captures normalise cleanly wins (SPEC-014). Pure; templates are loaded once at construction.
 */
@Service
class TemplateEngine implements ParseService {

    static final double TEMPLATE_CONFIDENCE = 1.0; // R-013-5

    private static final Logger log = LoggerFactory.getLogger(TemplateEngine.class);
    private static final Pattern EMAIL_IN_BRACKETS = Pattern.compile("<([^>]+)>");

    private final List<ParseTemplate> templates;

    TemplateEngine() {
        this(TemplateLoader.loadFromClasspath());
    }

    TemplateEngine(List<ParseTemplate> templates) {
        this.templates = List.copyOf(templates);
    }

    List<ParseTemplate> templates() {
        return templates;
    }

    @Override
    public Optional<ParsedMessage> parse(Source source, String sender, String body, Instant receivedAt) {
        if (source == null || sender == null || body == null || receivedAt == null) {
            return Optional.empty();
        }
        String senderKey = senderKey(source, sender);
        if (senderKey == null) {
            return Optional.empty();
        }
        for (ParseTemplate t : templates) {
            if (t.source() != source || !t.senderPattern().matcher(senderKey).matches()) {
                continue;
            }
            Matcher m = t.regex().matcher(body);
            if (!m.find()) {
                continue;
            }
            try {
                return Optional.of(new ParsedMessage(t.id(), t.version(), t.bank(), t.accountType(), t.recordType(),
                        fields(t, m, body, receivedAt), TEMPLATE_CONFIDENCE));
            } catch (NormalisationException e) {
                // R-013-3 / R-014-2: a capture that does not normalise means this template did not match.
                log.debug("event=template_normalisation_failed template={} reason={}", t.id(), e.getMessage());
            }
        }
        return Optional.empty();
    }

    private static ParsedFields fields(ParseTemplate t, Matcher m, String body, Instant receivedAt) {
        String amount = group(t, m, "amount");
        if (amount == null) {
            throw new NormalisationException("no amount captured");
        }
        String dirWord = group(t, m, "dir");
        Direction direction = dirWord != null ? Normaliser.direction(dirWord)
                : Normaliser.direction(t.fixed().get("direction"));
        String capturedChannel = group(t, m, "channel");
        Channel channel = Normaliser.channel(capturedChannel != null ? capturedChannel : t.fixed().get("channel"), body);
        String bal = group(t, m, "bal");
        return new ParsedFields(
                Normaliser.paise(amount),
                direction,
                Normaliser.last4(group(t, m, "last4")),
                channel,
                Normaliser.counterparty(group(t, m, "cp")),
                Normaliser.ref(group(t, m, "ref")),
                Normaliser.txnTime(group(t, m, "date"), group(t, m, "time"), group(t, m, "datetime"), receivedAt),
                bal == null ? null : Normaliser.paise(bal));
    }

    private static String group(ParseTemplate t, Matcher m, String name) {
        if (!t.groups().contains(name)) {
            return null;
        }
        String value = m.group(name);
        return value == null || value.isBlank() ? null : value;
    }

    /** SMS: the sender's stable middle part; EMAIL: the lowercased address. */
    static String senderKey(Source source, String sender) {
        if (source == Source.SMS) {
            return SenderIds.middlePart(sender).orElse(null);
        }
        Matcher m = EMAIL_IN_BRACKETS.matcher(sender);
        String address = m.find() ? m.group(1) : sender;
        return address.strip().toLowerCase(Locale.ROOT);
    }
}
