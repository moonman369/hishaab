package in.moonman.hishaab.auth.internal;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

/**
 * SPEC-000: health is open, everything else needs authentication. Auth failures are ProblemDetails (SPEC §4.4).
 */
@Configuration
class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, BasicAuthProperties basic) throws Exception {
        http
                .csrf(csrf -> csrf.disable()) // stateless JSON API, no cookies
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> writeProblem(res, 401, "UNAUTHORIZED",
                                "Unauthorized", "Authentication is required"))
                        .accessDeniedHandler((req, res, ex) -> writeProblem(res, 403, "FORBIDDEN",
                                "Forbidden", "Access is denied")))
                .formLogin(f -> f.disable())
                .logout(l -> l.disable());
        if (basic.enabled()) {
            http.httpBasic(b -> b.authenticationEntryPoint((req, res, ex) -> {
                res.setHeader("WWW-Authenticate", "Basic realm=\"hishaab\"");
                writeProblem(res, 401, "UNAUTHORIZED", "Unauthorized", "Authentication is required");
            }));
        }
        return http.build();
    }

    @Bean
    UserDetailsService users(BasicAuthProperties basic, PasswordEncoder encoder) {
        if (!basic.enabled()) {
            return new InMemoryUserDetailsManager(); // no users: nothing can log in until SPEC-050
        }
        if (!StringUtils.hasText(basic.username()) || !StringUtils.hasText(basic.password())) {
            throw new IllegalStateException(
                    "hishaab.security.basic-auth is enabled but username/password are not set (HISHAAB_DEV_USER / HISHAAB_DEV_PASSWORD)");
        }
        return new InMemoryUserDetailsManager(User.withUsername(basic.username())
                .password(encoder.encode(basic.password()))
                .roles("USER")
                .build());
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    private static void writeProblem(HttpServletResponse res, int status, String code, String title, String detail)
            throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        res.getWriter().write("{\"type\":\"https://hishaab/errors/" + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-')
                + "\",\"title\":\"" + title + "\",\"status\":" + status
                + ",\"detail\":\"" + detail + "\",\"code\":\"" + code + "\"}");
    }
}
