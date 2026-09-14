package org.springframework.samples.petclinic.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.samples.petclinic.repository.jdbc.JdbcAccountRepository;
import org.springframework.samples.petclinic.rest.dto.AuthResponseDto;
import org.springframework.samples.petclinic.rest.dto.AuthUserDto;
import org.springframework.samples.petclinic.rest.dto.LoginRequestDto;
import org.springframework.samples.petclinic.rest.dto.SignupRequestDto;
import org.springframework.samples.petclinic.security.AccessPolicy;
import org.springframework.samples.petclinic.security.Account;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {
    private final JdbcAccountRepository accounts;
    private final PasswordEncoder passwords;
    private final AuthenticationManager authentication;
    private final JwtEncoder tokens;
    private final AccessPolicy access;
    private final String issuer;
    private final long ttl;

    public AuthService(JdbcAccountRepository accounts, PasswordEncoder passwords, AuthenticationManager authentication,
                       JwtEncoder tokens, AccessPolicy access, @Value("${petclinic.jwt.issuer:petclinic-rest}") String issuer,
                       @Value("${petclinic.jwt.access-token-ttl:3600}") long ttl) {
        if (ttl < 60 || ttl > 86400) throw new IllegalArgumentException("JWT access token TTL must be 60-86400 seconds");
        this.accounts = accounts;
        this.passwords = passwords;
        this.authentication = authentication;
        this.tokens = tokens;
        this.access = access;
        this.issuer = issuer;
        this.ttl = ttl;
    }

    @Transactional
    public AuthUserDto signup(SignupRequestDto request) {
        if (request.getAuthCode() != null && !"OWNER".equals(request.getAuthCode())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Public signup only allows OWNER; an administrator must create staff accounts");
        }
        validatePassword(request.getPassword());
        if (List.of(request.getFirstName(), request.getLastName(), request.getAddress(), request.getCity()).stream().anyMatch(String::isBlank)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Profile fields must not be blank");
        }
        if (accounts.find(request.getUsername()) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }
        String encoded = passwords.encode(request.getPassword());
        try {
            int ownerId = accounts.createOwner(request);
            accounts.createOwnerAccount(request.getUsername(), encoded, ownerId);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username or profile already exists");
        }
        return profile(accounts.find(request.getUsername()));
    }

    @Transactional(readOnly = true)
    public AuthResponseDto login(LoginRequestDto request) {
        if (request.getPassword().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
        try {
            authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(request.getUsername(), request.getPassword()));
        } catch (AuthenticationException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }
        Account account = accounts.find(request.getUsername());
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of("petclinic-api"))
            .subject(account.username()).issuedAt(now).notBefore(now).expiresAt(now.plusSeconds(ttl))
            .id(UUID.randomUUID().toString()).claim("roles", account.roles()).claim("authCode", account.authCode());
        if (account.ownerId() != null) claims.claim("ownerId", account.ownerId());
        if (account.vetId() != null) claims.claim("vetId", account.vetId());
        String token = tokens.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims.build())).getTokenValue();
        return new AuthResponseDto().accessToken(token).tokenType("Bearer").expiresIn((int) ttl).user(profile(account));
    }

    @Transactional(readOnly = true)
    public AuthUserDto me() {
        return profile(access.currentAccount());
    }

    private AuthUserDto profile(Account account) {
        return new AuthUserDto().username(account.username()).authCode(account.authCode())
            .ownerId(account.ownerId()).vetId(account.vetId());
    }

    public static void validatePassword(String raw) {
        if (raw == null || raw.isBlank() || raw.length() < 8 || raw.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must have at least 8 characters and no more than 72 UTF-8 bytes");
        }
    }
}
