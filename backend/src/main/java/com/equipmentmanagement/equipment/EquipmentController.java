package com.equipmentmanagement.equipment;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/equipment")
public class EquipmentController {
    private static final List<String> STATUSES = List.of("IN_STOCK", "IN_TASK", "MAINTENANCE", "DISABLED");
    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;

    public EquipmentController(JdbcTemplate jdbc, CurrentUser currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String keyword,
                                           @RequestParam(required = false) Long categoryId,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) Long unitId) {
        String term = keyword == null ? "" : keyword.trim();
        return jdbc.queryForList("""
                SELECT e.id, e.equipment_code, e.name, e.status, e.location, e.department, e.attributes::text AS attributes,
                       e.category_id, e.unit_id, u.name AS unit_name, e.created_by, c.name AS category_name, e.created_at, e.updated_at,
                       task.name AS task_name, image.id AS image_attachment_id
                FROM equipment e JOIN equipment_categories c ON c.id = e.category_id
                JOIN organization_units u ON u.id = e.unit_id
                LEFT JOIN LATERAL (SELECT t.name FROM task_equipment te JOIN tasks t ON t.id = te.task_id
                    WHERE te.equipment_id = e.id AND te.returned_at IS NULL AND t.status = 'EXECUTING' ORDER BY te.id DESC LIMIT 1) task ON TRUE
                LEFT JOIN LATERAL (SELECT a.id FROM attachments a WHERE a.equipment_id = e.id
                    AND a.file_type LIKE 'image/%' ORDER BY a.id DESC LIMIT 1) image ON TRUE
                WHERE (? = '' OR e.name ILIKE '%' || ? || '%' OR e.equipment_code ILIKE '%' || ? || '%')
                  AND (CAST(? AS BIGINT) IS NULL OR e.category_id = ?)
                  AND (CAST(? AS VARCHAR) IS NULL OR e.status = ?)
                  AND (CAST(? AS BIGINT) IS NULL OR e.unit_id IN (SELECT id FROM organization_units WHERE id = ? OR parent_id = ? OR parent_id IN (SELECT id FROM organization_units WHERE parent_id = ?)))
                ORDER BY e.id DESC
                """, term, term, term, categoryId, categoryId, status, status, unitId, unitId, unitId, unitId);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable long id) {
        List<Map<String, Object>> result = jdbc.queryForList("""
                SELECT e.id, e.equipment_code, e.category_id, e.unit_id, unit.name AS unit_name, e.name, e.status, e.location, e.department,
                       e.attributes::text AS attributes, e.created_by, e.updated_by, e.created_at, e.updated_at, e.disabled_at,
                       c.name AS category_name, creator.name AS creator_name, updater.name AS updater_name
                FROM equipment e
                JOIN equipment_categories c ON c.id = e.category_id
                JOIN organization_units unit ON unit.id = e.unit_id
                JOIN users creator ON creator.id = e.created_by
                JOIN users updater ON updater.id = e.updated_by
                WHERE e.id = ?
                """, id);
        if (result.isEmpty()) throw new IllegalArgumentException("装备不存在");
        return result.getFirst();
    }

    @PostMapping
    public Map<String, Object> create(@Valid @RequestBody EquipmentRequest request) {
        AppUser user = currentUser.get();
        ensureCategoryActive(request.categoryId());
        String code = nextCode();
        Long id = jdbc.queryForObject("""
                INSERT INTO equipment(equipment_code, category_id, unit_id, name, status, location, department, attributes, created_by, updated_by)
                VALUES (?, ?, ?, ?, 'IN_STOCK', ?, ?, CAST(? AS jsonb), ?, ?) RETURNING id
                """, Long.class, code, request.categoryId(), request.unitId(), request.name(), emptyToNull(request.location()),
                emptyToNull(request.department()), safeAttributes(request.attributes()), user.id(), user.id());
        addEvent(id, null, "CREATED", null, "IN_STOCK", user.id(), "创建装备");
        return Map.of("id", id, "equipmentCode", code);
    }

    @PutMapping("/{id}")
    public Map<String, Long> update(@PathVariable long id, @Valid @RequestBody EquipmentRequest request) {
        AppUser user = currentUser.get();
        Map<String, Object> existing = getForEdit(id);
        ensureOwnerOrAdmin(existing, user);
        ensureCategoryActive(request.categoryId());
        jdbc.update("""
                UPDATE equipment SET category_id = ?, unit_id = ?, name = ?, location = ?, department = ?,
                       attributes = CAST(? AS jsonb), updated_by = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, request.categoryId(), request.unitId(), request.name(), emptyToNull(request.location()), emptyToNull(request.department()),
                safeAttributes(request.attributes()), user.id(), id);
        return Map.of("id", id);
    }

    @PostMapping("/{id}/status")
    public Map<String, Long> changeStatus(@PathVariable long id, @Valid @RequestBody StatusRequest request) {
        AppUser user = currentUser.get();
        Map<String, Object> existing = getForEdit(id);
        ensureOwnerOrAdmin(existing, user);
        if (!STATUSES.contains(request.status())) throw new IllegalArgumentException("不支持的装备状态");
        String oldStatus = (String) existing.get("status");
        if ("IN_TASK".equals(oldStatus) && !"IN_TASK".equals(request.status())) {
            Long openAssignments = jdbc.queryForObject("SELECT COUNT(*) FROM task_equipment WHERE equipment_id = ? AND returned_at IS NULL", Long.class, id);
            if (openAssignments != null && openAssignments > 0) throw new IllegalStateException("装备存在未归还任务，不能直接变更状态");
        }
        jdbc.update("UPDATE equipment SET status = ?, disabled_at = CASE WHEN ? = 'DISABLED' THEN CURRENT_TIMESTAMP ELSE NULL END, updated_by = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                request.status(), request.status(), user.id(), id);
        addEvent(id, null, "STATUS_CHANGED", oldStatus, request.status(), user.id(), request.description());
        return Map.of("id", id);
    }

    @PutMapping("/{id}/unit")
    public Map<String, Long> changeUnit(@PathVariable long id, @Valid @RequestBody UnitRequest request) {
        AppUser user = currentUser.get();
        Map<String, Object> existing = getForEdit(id);
        ensureOwnerOrAdmin(existing, user);
        String oldUnitName = jdbc.queryForObject("""
                SELECT u.name FROM equipment e JOIN organization_units u ON u.id = e.unit_id WHERE e.id = ?
                """, String.class, id);
        Long unitExists = jdbc.queryForObject("SELECT COUNT(*) FROM organization_units WHERE id = ?", Long.class, request.unitId());
        if (unitExists == null || unitExists == 0) throw new IllegalArgumentException("所属单位不存在");
        String newUnitName = jdbc.queryForObject("SELECT name FROM organization_units WHERE id = ?", String.class, request.unitId());
        if (request.unitId() == ((Number) jdbc.queryForObject("SELECT unit_id FROM equipment WHERE id = ?", Number.class, id)).longValue()) {
            return Map.of("id", id);
        }
        jdbc.update("UPDATE equipment SET unit_id = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", request.unitId(), user.id(), id);
        addEvent(id, null, "UNIT_CHANGED", null, null, user.id(), "所属单位：" + oldUnitName + " → " + newUnitName);
        return Map.of("id", id);
    }

    @GetMapping("/{id}/events")
    public List<Map<String, Object>> events(@PathVariable long id) {
        return jdbc.queryForList("""
                SELECT ev.*, u.name AS operator_name
                FROM equipment_events ev LEFT JOIN users u ON u.id = ev.operator_id
                WHERE ev.equipment_id = ? ORDER BY ev.occurred_at DESC, ev.id DESC
                """, id);
    }

    @GetMapping("/history")
    public List<Map<String, Object>> history(@RequestParam(required = false) Long equipmentId,
                                              @RequestParam(required = false) Long taskId,
                                              @RequestParam(required = false) String eventType,
                                              @RequestParam(required = false) String from,
                                              @RequestParam(required = false) String to) {
        return jdbc.queryForList("""
                SELECT ev.id, ev.event_type, ev.old_status, ev.new_status, ev.handler_name, ev.description, ev.occurred_at,
                       e.id AS equipment_id, e.equipment_code, e.name AS equipment_name, t.id AS task_id, t.task_code,
                       u.name AS operator_name
                FROM equipment_events ev
                JOIN equipment e ON e.id = ev.equipment_id
                LEFT JOIN task_equipment te ON te.id = ev.task_equipment_id
                LEFT JOIN tasks t ON t.id = te.task_id
                LEFT JOIN users u ON u.id = ev.operator_id
                WHERE (CAST(? AS BIGINT) IS NULL OR ev.equipment_id = ?)
                  AND (CAST(? AS BIGINT) IS NULL OR t.id = ?)
                  AND (CAST(? AS VARCHAR) IS NULL OR ev.event_type = ?)
                  AND (CAST(? AS timestamptz) IS NULL OR ev.occurred_at >= CAST(? AS timestamptz))
                  AND (CAST(? AS timestamptz) IS NULL OR ev.occurred_at <= CAST(? AS timestamptz))
                ORDER BY ev.occurred_at DESC, ev.id DESC
                """, equipmentId, equipmentId, taskId, taskId, eventType, eventType, from, from, to, to);
    }

    private Map<String, Object> getForEdit(long id) {
        List<Map<String, Object>> items = jdbc.queryForList("SELECT id, created_by, status FROM equipment WHERE id = ?", id);
        if (items.isEmpty()) throw new IllegalArgumentException("装备不存在");
        return items.getFirst();
    }

    private void ensureOwnerOrAdmin(Map<String, Object> existing, AppUser user) {
        Number ownerId = (Number) existing.get("created_by");
        if (!user.isAdmin() && ownerId.longValue() != user.id()) throw new IllegalArgumentException("仅创建者或管理者可编辑该装备");
    }

    private void ensureCategoryActive(long categoryId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM equipment_categories WHERE id = ? AND status = 'ACTIVE'", Long.class, categoryId);
        if (count == null || count == 0) throw new IllegalArgumentException("装备类别不存在或已停用");
    }

    private String nextCode() {
        Long next = jdbc.queryForObject("SELECT nextval('equipment_code_seq')", Long.class);
        return "EQ-%06d".formatted(next);
    }

    private void addEvent(long equipmentId, Long taskEquipmentId, String type, String oldStatus, String newStatus,
                          long operatorId, String description) {
        jdbc.update("""
                INSERT INTO equipment_events(equipment_id, task_equipment_id, event_type, old_status, new_status, operator_id, description)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, equipmentId, taskEquipmentId, type, oldStatus, newStatus, operatorId, emptyToNull(description));
    }

    private String safeAttributes(String attributes) {
        return attributes == null || attributes.isBlank() ? "{}" : attributes;
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record EquipmentRequest(long categoryId, long unitId, @NotBlank String name, String location, String department, String attributes) { }
    public record UnitRequest(long unitId) { }
    public record StatusRequest(@NotBlank String status, String description) { }
}
