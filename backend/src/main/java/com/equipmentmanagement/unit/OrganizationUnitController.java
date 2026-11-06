package com.equipmentmanagement.unit;

import com.equipmentmanagement.auth.AppUser;
import com.equipmentmanagement.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/units")
public class OrganizationUnitController {
    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;

    public OrganizationUnitController(JdbcTemplate jdbc, CurrentUser currentUser) { this.jdbc = jdbc; this.currentUser = currentUser; }

    @GetMapping
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("SELECT id, name, parent_id, level, created_at FROM organization_units ORDER BY level, name, id");
    }

    @PostMapping
    public Map<String, Object> create(@Valid @RequestBody UnitRequest request) {
        AppUser user = currentUser.get();
        if (!user.isAdmin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有管理者可以维护单位");
        int level = 1;
        if (request.parentId() != null) {
            List<Integer> parents = jdbc.query("SELECT level FROM organization_units WHERE id = ?", (rs, row) -> rs.getInt(1), request.parentId());
            if (parents.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上级单位不存在");
            level = parents.getFirst() + 1;
        }
        if (level > 3) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单位层级最多三级");
        try {
            Long id = jdbc.queryForObject("INSERT INTO organization_units(name, parent_id, level, created_by) VALUES (?, ?, ?, ?) RETURNING id",
                    Long.class, request.name().trim(), request.parentId(), level, user.id());
            return Map.of("id", id, "level", level);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "同一上级单位下名称不能重复");
        }
    }

    @PutMapping("/{id}")
    public Map<String, Object> rename(@PathVariable long id, @Valid @RequestBody RenameRequest request) {
        AppUser user = currentUser.get();
        if (!user.isAdmin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有管理者可以维护单位");
        try {
            Integer changed = jdbc.queryForObject("""
                    UPDATE organization_units SET name = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? RETURNING 1
                    """, Integer.class, request.name().trim(), id);
            if (changed == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "单位不存在");
            return Map.of("id", id, "name", request.name().trim());
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "同一上级单位下名称不能重复");
        }
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        AppUser user = currentUser.get();
        if (!user.isAdmin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有管理者可以维护单位");
        List<Map<String, Object>> target = jdbc.queryForList("SELECT id, name FROM organization_units WHERE id = ?", id);
        if (target.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "单位不存在");
        Long unassignedId = jdbc.queryForObject("SELECT id FROM organization_units WHERE name = '未分配单位' AND parent_id IS NULL", Long.class);
        if (unassignedId == null || unassignedId == id) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能删除未分配单位");
        List<Long> unitIds = jdbc.query("""
                WITH RECURSIVE subtree AS (
                    SELECT id, parent_id, level FROM organization_units WHERE id = ?
                    UNION ALL
                    SELECT child.id, child.parent_id, child.level FROM organization_units child JOIN subtree parent ON child.parent_id = parent.id
                ) SELECT id FROM subtree ORDER BY level DESC
                """, (rs, row) -> rs.getLong(1), id);
        for (Long unitId : unitIds) {
            jdbc.update("UPDATE equipment SET unit_id = ?, updated_at = CURRENT_TIMESTAMP WHERE unit_id = ?", unassignedId, unitId);
        }
        for (Long unitId : unitIds) jdbc.update("DELETE FROM organization_units WHERE id = ?", unitId);
        return Map.of("deleted", unitIds.size(), "equipmentMovedToUnitId", unassignedId);
    }

    public record UnitRequest(@NotBlank @Size(max = 150) String name, Long parentId) { }
    public record RenameRequest(@NotBlank @Size(max = 150) String name) { }
}
