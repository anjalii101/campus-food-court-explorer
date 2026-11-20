package com.campusfoodcourt.campus_foodcourt.controller;

import com.campusfoodcourt.campus_foodcourt.auth.InvalidRefreshTokenException;
import com.campusfoodcourt.campus_foodcourt.auth.RefreshTokenCookies;
import com.campusfoodcourt.campus_foodcourt.dto.AuthRequest;
import com.campusfoodcourt.campus_foodcourt.dto.AuthResponse;
import com.campusfoodcourt.campus_foodcourt.dto.RegisterRequest;
import com.campusfoodcourt.campus_foodcourt.dto.UserResponse;
import com.campusfoodcourt.campus_foodcourt.entity.User;
import com.campusfoodcourt.campus_foodcourt.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Access tokens are returned in the JSON body and sent back as {@code Authorization: Bearer}.
 * Refresh tokens live only in an HttpOnly cookie; every endpoint that reads or sets that cookie
 * requires a CSRF token (see SecurityConfig), obtained from {@code GET /api/auth/csrf}.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenCookies refreshTokenCookies;

    public AuthController(AuthService authService, RefreshTokenCookies refreshTokenCookies) {
        this.authService = authService;
        this.refreshTokenCookies = refreshTokenCookies;
    }

    /**
     * Issues the CSRF token to send in the X-XSRF-TOKEN header. It is also set as the host-only
     * {@code __Host-XSRF-TOKEN} cookie; a frontend that cannot read that cookie (e.g. on another origin)
     * uses the token from this response body.
     */
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken csrfToken) {
        return Map.of("headerName", csrfToken.getHeaderName(), "token", csrfToken.getToken());
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        User user = User.builder()
                .name(request.name().trim())
                .email(request.email())
                .password(request.password())
                .build();
        return withRefreshCookie(HttpStatus.CREATED, authService.register(user));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody AuthRequest request) {
        return withRefreshCookie(HttpStatus.OK, authService.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(HttpServletRequest request) {
        String refreshToken = refreshTokenCookies.read(request)
                .orElseThrow(() -> new InvalidRefreshTokenException("Refresh token cookie is missing"));
        return withRefreshCookie(HttpStatus.OK, authService.refresh(refreshToken));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        refreshTokenCookies.read(request).ifPresent(authService::logout);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshTokenCookies.clear().toString())
                .build();
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal UserDetails principal) {
        return UserResponse.from(authService.getByEmail(principal.getUsername()));
    }

    private ResponseEntity<AuthResponse> withRefreshCookie(HttpStatus status, AuthResponse body) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE,
                        refreshTokenCookies.issue(body.getRefreshToken(), body.getRefreshTokenExpiresAt()).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }
}
