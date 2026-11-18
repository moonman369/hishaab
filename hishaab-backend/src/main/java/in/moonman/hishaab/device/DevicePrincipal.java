package in.moonman.hishaab.device;

import java.util.UUID;

/** The authenticated principal for requests made with a device token (role {@code DEVICE}). */
public record DevicePrincipal(UUID deviceId) {
}
