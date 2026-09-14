package org.springframework.samples.petclinic.security;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

@Configuration
public class JwtTokenConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    AuthenticationManager authenticationManager(AccountDetailsService details, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(details);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean
    SecretKey jwtSigningKey(@Value("${petclinic.jwt.secret:}") String configuredSecret) {
        byte[] secret;
        if (configuredSecret.isBlank()) {
            secret = new byte[32];
            new SecureRandom().nextBytes(secret);
        } else {
            secret = Base64.getDecoder().decode(configuredSecret);
            if (secret.length < 32) throw new IllegalArgumentException("JWT_SECRET must contain at least 32 random bytes, Base64 encoded");
        }
        return new SecretKeySpec(secret, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey key) {
        return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(key));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey key, @Value("${petclinic.jwt.issuer:petclinic-rest}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            new JwtTimestampValidator(Duration.ZERO), new JwtIssuerValidator(issuer),
            new JwtClaimValidator<String>("sub", value -> value != null && !value.isBlank()),
            new JwtClaimValidator<java.time.Instant>("exp", java.util.Objects::nonNull),
            new JwtClaimValidator<java.util.List<String>>("aud", value -> value != null && value.contains("petclinic-api"))));
        return decoder;
    }
}
