package com.equipmentmanagement.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpHeaders;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request, HttpServletResponse response) {
        Long existing = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username = ?", Long.class, request.username());
        if (existing != null && existing > 0) {
            throw new IllegalArgumentException("用户名已被使用");
        }
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        String role = count != null && count == 0 ? "ADMIN" : "USER";
        String name = "ADMIN".equals(role) ? "管理者" : request.name();
        Long id = jdbc.queryForObject("""
                INSERT INTO users(username, password_hash, name, role)
                VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, request.username(), passwordEncoder.encode(request.password()), name, role);
        AppUser user = new AppUser(id, request.username(), name, role, "ACTIVE");
        return authenticate(user, response);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        AppUser user = jdbc.query("SELECT id, username, name, role, status FROM users WHERE username = ?", rs -> {
            if (!rs.next()) return null;
            return new AppUser(rs.getLong("id"), rs.getString("username"), rs.getString("name"),
                    rs.getString("role"), rs.getString("status"));
        }, request.username());
        String hash = jdbc.query("SELECT password_hash FROM users WHERE username = ?", rs -> rs.next() ? rs.getString(1) : null,
                request.username());
        if (user == null || hash == null || !passwordEncoder.matches(request.password(), hash)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        if (!"ACTIVE".equals(user.status())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号已停用");
        }
        return authenticate(user, response);
    }

    private AuthResponse authenticate(AppUser user, HttpServletResponse response) {
        String token = jwtService.create(user);
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from("equipment_access", token)
                .httpOnly(true).sameSite("Lax").path("/api").maxAge(java.time.Duration.ofHours(24)).build().toString());
        return AuthResponse.from(user, token);
    }

    public record RegisterRequest(@NotBlank @Size(min = 3, max = 50) String username,
                                  @NotBlank @Size(min = 6, max = 100) String password,
                                  @NotBlank @Size(max = 100) String name) { }
    public record LoginRequest(@NotBlank String username, @NotBlank String password) { }
    public record AuthResponse(Long id, String username, String name, String role, String token) {
        static AuthResponse from(AppUser user, String token) {
            return new AuthResponse(user.id(), user.username(), user.name(), user.role(), token);
        }
    }
}
