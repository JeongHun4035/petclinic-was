package org.springframework.samples.petclinic.rest.controller.v1;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.samples.petclinic.rest.api.AuthApi;
import org.springframework.samples.petclinic.rest.dto.*;
import org.springframework.samples.petclinic.service.AuthService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@CrossOrigin
public class AuthRestControllerV1 implements AuthApi {
    private final AuthService service;

    public AuthRestControllerV1(AuthService service) { this.service = service; }

    @Override
    @io.swagger.v3.oas.annotations.security.SecurityRequirements
    public ResponseEntity<AuthUserDto> signup(SignupRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.signup(request));
    }

    @Override
    @io.swagger.v3.oas.annotations.security.SecurityRequirements
    public ResponseEntity<AuthResponseDto> login(LoginRequestDto request) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("Pragma", "no-cache").body(service.login(request));
    }

    @Override
    public ResponseEntity<AuthUserDto> getCurrentUser() { return ResponseEntity.ok(service.me()); }
}
