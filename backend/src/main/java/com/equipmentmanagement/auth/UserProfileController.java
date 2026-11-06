package com.equipmentmanagement.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/me")
public class UserProfileController {
    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;
    private final JwtService jwtService;

    public UserProfileController(JdbcTemplate jdbc, CurrentUser currentUser, JwtService jwtService) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.jwtService = jwtService;
    }

    @GetMapping
    public Map<String, Object> profile() {
        return find(currentUser.get().id());
    }

    @PutMapping
    public AuthController.AuthResponse update(@Valid @RequestBody ProfileRequest request) {
        AppUser principal = currentUser.get();
        String name = request.name().trim();
        jdbc.update("""
                UPDATE users SET name = ?, work_unit = ?, job_title = ?, phone = ?, email = ?, address = ?, remarks = ?,
                    updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, name, empty(request.workUnit()), empty(request.jobTitle()), empty(request.phone()), empty(request.email()),
                empty(request.address()), empty(request.remarks()), principal.id());
        Map<String, Object> data = find(principal.id());
        AppUser user = new AppUser(principal.id(), (String) data.get("username"), (String) data.get("name"),
                (String) data.get("role"), (String) data.get("status"));
        return AuthController.AuthResponse.from(user, jwtService.create(user));
    }

    private Map<String, Object> find(long id) {
        var rows = jdbc.queryForList("""
                SELECT id, username, name, role, status, work_unit, job_title, phone, email, address, remarks, created_at, updated_at
                FROM users WHERE id = ?
                """, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("用户不存在");
        return rows.getFirst();
    }

    private String empty(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public record ProfileRequest(@NotBlank @Size(max = 100) String name,
                                 @Size(max = 200) String workUnit,
                                 @Size(max = 100) String jobTitle,
                                 @Size(max = 50) String phone,
                                 @Size(max = 150) String email,
                                 @Size(max = 300) String address,
                                 @Size(max = 2000) String remarks) { }
}
