package in.moonman.hishaab.auth.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Temporary single-user HTTP Basic login used until real auth (SPEC-050).
 * Enabled in {@code dev} and {@code test}; disabled in {@code prod}. Credentials come from env vars.
 */
@ConfigurationProperties(prefix = "hishaab.security.basic-auth")
record BasicAuthProperties(boolean enabled, String username, String password) {
}
