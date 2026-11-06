package com.equipmentmanagement.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/users")
public class UserManagementController {
    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;

    public UserManagementController(JdbcTemplate jdbc, CurrentUser currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        requireAdmin();
        return jdbc.queryForList("""
                SELECT id, username, name, role, status, work_unit, job_title, phone, email, created_at, updated_at
                FROM users ORDER BY id ASC
                """);
    }

    @PutMapping("/{id}/role")
    public Map<String, Object> updateRole(@PathVariable long id, @Valid @RequestBody RoleRequest request) {
        AppUser operator = requireAdmin();
        String role = request.role().trim().toUpperCase();
        if (!List.of("ADMIN", "USER").contains(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户类型只能是管理者或普通用户");
        }
        if (operator.id() == id) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能修改自己的用户类型");
        }
        Integer changed = jdbc.queryForObject("""
                UPDATE users SET role = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? RETURNING 1
                """, Integer.class, role, id);
        if (changed == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        return Map.of("id", id, "role", role);
    }

    private AppUser requireAdmin() {
        AppUser user = currentUser.get();
        if (!user.isAdmin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有管理者可以管理用户类型");
        return user;
    }

    public record RoleRequest(@NotBlank String role) { }
}
