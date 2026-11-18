package in.moonman.hishaab.auth.internal;

import in.moonman.hishaab.device.DevicePrincipal;
import in.moonman.hishaab.device.DeviceService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * R-010-2/4: {@code Authorization: Bearer hdt_...} authenticates a device with role {@code DEVICE} only.
 * An unknown or revoked device token is rejected with 401 right here; it never falls through to other schemes.
 * Not a Spring bean on purpose, so it is not also registered as a plain servlet filter.
 */
class DeviceTokenFilter extends OncePerRequestFilter {

    private static final String BEARER_DEVICE = "Bearer hdt_";

    private final DeviceService devices;

    DeviceTokenFilter(DeviceService devices) {
        this.devices = devices;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_DEVICE)) {
            chain.doFilter(request, response);
            return;
        }
        String token = header.substring("Bearer ".length()).strip();
        Optional<DevicePrincipal> device = devices.authenticate(token);
        if (device.isEmpty()) {
            SecurityConfig.writeProblem(response, 401, "UNAUTHORIZED", "Unauthorized",
                    "Device token is invalid or revoked");
            return;
        }
        var authentication = UsernamePasswordAuthenticationToken.authenticated(device.get(), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + Roles.DEVICE)));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
