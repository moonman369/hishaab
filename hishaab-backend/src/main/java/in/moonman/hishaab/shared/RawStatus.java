package in.moonman.hishaab.shared;

/** {@code raw_message.status} (SPEC §4.3). Only this and {@code ignore_reason} may ever change on a raw message (H-2). */
public enum RawStatus {
    NEW, CLASSIFIED, PARSED, IGNORED, FAILED, ERROR
}
