package com.equipmentmanagement.category;

import com.equipmentmanagement.auth.AppUser;
import com.equipmentmanagement.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {
    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;

    public CategoryController(JdbcTemplate jdbc, CurrentUser currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("SELECT id, name, description, status, created_at FROM equipment_categories WHERE status = 'ACTIVE' ORDER BY name");
    }

    @PostMapping
    public Map<String, Long> create(@Valid @RequestBody CategoryRequest request) {
        AppUser user = requireAdmin();
        Long id = jdbc.queryForObject("""
                INSERT INTO equipment_categories(name, description, created_by) VALUES (?, ?, ?) RETURNING id
                """, Long.class, request.name(), request.description(), user.id());
        return Map.of("id", id);
    }

    @GetMapping("/{id}/fields")
    public List<Map<String, Object>> fields(@PathVariable long id) {
        return jdbc.queryForList("SELECT id, field_name, field_key, field_type, required, options::text AS options, sort_order FROM category_fields WHERE category_id = ? ORDER BY sort_order, id", id);
    }

    @PostMapping("/{id}/fields")
    public Map<String, Long> addField(@PathVariable long id, @Valid @RequestBody FieldRequest request) {
        requireAdmin();
        if (!List.of("TEXT", "TEXTAREA", "INTEGER", "DECIMAL", "DATE", "DATETIME", "BOOLEAN", "SELECT").contains(request.fieldType())) {
            throw new IllegalArgumentException("不支持的字段类型");
        }
        Long fieldId = jdbc.queryForObject("""
                INSERT INTO category_fields(category_id, field_name, field_key, field_type, required, options, sort_order)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?) RETURNING id
                """, Long.class, id, request.fieldName(), request.fieldKey(), request.fieldType(), request.required(),
                request.options() == null ? null : request.options(), request.sortOrder());
        return Map.of("id", fieldId);
    }

    @PutMapping("/{categoryId}/fields/{fieldId}")
    public Map<String, Long> updateField(@PathVariable long categoryId, @PathVariable long fieldId,
                                         @Valid @RequestBody FieldRequest request) {
        requireAdmin();
        if (!List.of("TEXT", "TEXTAREA", "INTEGER", "DECIMAL", "DATE", "DATETIME", "BOOLEAN", "SELECT").contains(request.fieldType())) {
            throw new IllegalArgumentException("Invalid field type");
        }
        int updated = jdbc.update("""
                UPDATE category_fields SET field_name = ?, field_key = ?, field_type = ?, required = ?,
                    options = CAST(? AS jsonb), sort_order = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND category_id = ?
                """, request.fieldName(), request.fieldKey(), request.fieldType(), request.required(),
                request.options(), request.sortOrder(), fieldId, categoryId);
        if (updated == 0) throw new IllegalArgumentException("Field not found");
        return Map.of("id", fieldId);
    }

    @DeleteMapping("/{categoryId}/fields/{fieldId}")
    public void deleteField(@PathVariable long categoryId, @PathVariable long fieldId) {
        requireAdmin();
        int deleted = jdbc.update("DELETE FROM category_fields WHERE id = ? AND category_id = ?", fieldId, categoryId);
        if (deleted == 0) throw new IllegalArgumentException("Field not found");
    }

    private AppUser requireAdmin() {
        AppUser user = currentUser.get();
        if (!user.isAdmin()) throw new IllegalArgumentException("仅管理者可维护装备类别和字段");
        return user;
    }

    public record CategoryRequest(@NotBlank String name, String description) { }
    public record FieldRequest(@NotBlank String fieldName, @NotBlank String fieldKey, @NotBlank String fieldType,
                               boolean required, String options, int sortOrder) { }
}
