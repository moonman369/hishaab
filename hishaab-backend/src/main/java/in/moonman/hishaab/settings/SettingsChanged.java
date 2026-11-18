package in.moonman.hishaab.settings;

/** Published once per key whose stored value actually changed (R-001-4). */
public record SettingsChanged(String key) {
}
