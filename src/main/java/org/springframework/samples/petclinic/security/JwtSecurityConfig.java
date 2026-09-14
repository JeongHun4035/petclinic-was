package org.springframework.samples.petclinic.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.samples.petclinic.repository.jdbc.JdbcAccountRepository;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableMethodSecurity
@ConditionalOnProperty(name = "petclinic.security.enable", havingValue = "true", matchIfMissing = true)
public class JwtSecurityConfig {
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JdbcAccountRepository accounts, ObjectMapper mapper) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable).cors(cors -> {})
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/auth/signup", "/api/auth/login").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/actuator/health").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) -> writeError(response, mapper, HttpStatus.UNAUTHORIZED, "Authentication required"))
                .accessDeniedHandler((request, response, exception) -> writeError(response, mapper, HttpStatus.FORBIDDEN, "Access denied")))
            .oauth2ResourceServer(resource -> resource
                .authenticationEntryPoint((request, response, exception) -> writeError(response, mapper, HttpStatus.UNAUTHORIZED, "Invalid or expired token"))
                .jwt(jwt -> jwt.jwtAuthenticationConverter(token -> {
                    // Re-read enabled state and roles so revoked privileges do not survive in an older token.
                    Account account = accounts.find(token.getSubject());
                    if (account == null || !account.enabled()) throw new BadCredentialsException("Invalid token account");
                    return new JwtAuthenticationToken(token, account.roles().stream().map(SimpleGrantedAuthority::new).toList(), account.username());
                }))).build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(java.util.List.of("*"));
        configuration.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(java.util.List.of("Authorization", "Content-Type", "Accept"));
        configuration.setExposedHeaders(java.util.List.of("Location"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private static void writeError(jakarta.servlet.http.HttpServletResponse response, ObjectMapper mapper,
                                   HttpStatus status, String detail) throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        if (status == HttpStatus.UNAUTHORIZED) response.setHeader("WWW-Authenticate", "Bearer");
        mapper.writeValue(response.getWriter(), java.util.Map.of("type", "about:blank", "title", status.getReasonPhrase(),
            "status", status.value(), "detail", detail));
    }
}
