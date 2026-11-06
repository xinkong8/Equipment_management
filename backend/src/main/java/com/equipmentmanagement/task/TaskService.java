package com.equipmentmanagement.task;

import com.equipmentmanagement.auth.AppUser;
import com.equipmentmanagement.auth.CurrentUser;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskService {
    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;

    public TaskService(JdbcTemplate jdbc, CurrentUser currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    @Transactional
    public Map<String, Object> create(CreateTaskRequest request) {
        AppUser user = currentUser.get();
        if (request.equipmentIds() == null || request.equipmentIds().isEmpty()) {
            throw new IllegalArgumentException("任务至少需要选择一件装备");
        }
        if (new HashSet<>(request.equipmentIds()).size() != request.equipmentIds().size()) {
            throw new IllegalArgumentException("任务中不能重复选择同一件装备");
        }
        String placeholders = request.equipmentIds().stream().map(id -> "?").collect(Collectors.joining(","));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, equipment_code, status FROM equipment WHERE id IN (" + placeholders + ") FOR UPDATE",
                request.equipmentIds().toArray());
        if (rows.size() != request.equipmentIds().size()) throw new IllegalArgumentException("存在不存在的装备");
        List<String> unavailable = rows.stream()
                .filter(row -> !"IN_STOCK".equals(row.get("status")))
                .map(row -> (String) row.get("equipment_code"))
                .toList();
        if (!unavailable.isEmpty()) throw new IllegalStateException("以下装备当前不可用：" + String.join("、", unavailable));

        String taskCode = nextCode();
        Long taskId = jdbc.queryForObject("""
                INSERT INTO tasks(task_code, name, type, location, start_time, end_time, status, leader, participants, description, created_by, updated_by)
                VALUES (?, ?, ?, ?, CAST(? AS timestamptz), CAST(? AS timestamptz), 'EXECUTING', ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, taskCode, request.name(), nullable(request.type()), nullable(request.location()),
                nullable(request.startTime()), nullable(request.endTime()), nullable(request.leader()), nullable(request.participants()),
                nullable(request.description()), user.id(), user.id());
        for (Map<String, Object> row : rows) {
            Long equipmentId = ((Number) row.get("id")).longValue();
            Long assignmentId = jdbc.queryForObject("""
                    INSERT INTO task_equipment(task_id, equipment_id, assigned_user, usage_description)
                    VALUES (?, ?, ?, ?) RETURNING id
                    """, Long.class, taskId, equipmentId, user.name(), nullable(request.description()));
            jdbc.update("UPDATE equipment SET status = 'IN_TASK', updated_by = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", user.id(), equipmentId);
            addEvent(equipmentId, assignmentId, "TASK_ASSIGNED", "IN_STOCK", "IN_TASK", user.id(), "分配至任务 " + taskCode);
        }
        return Map.of("id", taskId, "taskCode", taskCode);
    }

    @Transactional
    public void returnEquipment(long taskId, long equipmentId, ReturnEquipmentRequest request) {
        AppUser user = currentUser.get();
        requireTaskEditor(taskId, user);
        if (!List.of("IN_STOCK", "MAINTENANCE").contains(request.returnStatus())) {
            throw new IllegalArgumentException("归还后的装备状态只能为在库或维护保养中");
        }
        List<Map<String, Object>> assignments = jdbc.queryForList("""
                SELECT te.id, e.status FROM task_equipment te JOIN equipment e ON e.id = te.equipment_id
                WHERE te.task_id = ? AND te.equipment_id = ? AND te.returned_at IS NULL FOR UPDATE
                """, taskId, equipmentId);
        if (assignments.isEmpty()) throw new IllegalArgumentException("该装备不在当前任务中，或已经归还");
        Long assignmentId = ((Number) assignments.getFirst().get("id")).longValue();
        jdbc.update("""
                UPDATE task_equipment SET returned_at = CURRENT_TIMESTAMP, returned_user = ?, return_status = ?,
                    mileage = ?, damage_description = ?, transfer_description = ?, usage_description = COALESCE(?, usage_description),
                    updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, user.name(), request.returnStatus(), request.mileage(), nullable(request.damageDescription()),
                nullable(request.transferDescription()), nullable(request.usageDescription()), assignmentId);
        jdbc.update("UPDATE equipment SET status = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                request.returnStatus(), user.id(), equipmentId);
        String description = nullable(request.description());
        if (description == null) description = "从任务归还";
        addEvent(equipmentId, assignmentId, "TASK_RETURNED", "IN_TASK", request.returnStatus(), user.id(), description);
    }

    @Transactional
    public void recordEquipmentEvent(long taskId, long equipmentId, TaskEquipmentEventRequest request) {
        AppUser user = currentUser.get();
        requireTaskEditor(taskId, user);
        if (!List.of("TRANSFER", "DAMAGE", "LOST", "ANOMALY").contains(request.eventType())) {
            throw new IllegalArgumentException("不支持的任务装备事件类型");
        }
        if (nullable(request.description()) == null) throw new IllegalArgumentException("请填写事件说明");
        List<Map<String, Object>> assignments = jdbc.queryForList("""
                SELECT te.id FROM task_equipment te WHERE te.task_id = ? AND te.equipment_id = ?
                AND te.returned_at IS NULL FOR UPDATE
                """, taskId, equipmentId);
        if (assignments.isEmpty()) throw new IllegalArgumentException("该装备不在当前任务中，或已经归还");
        long assignmentId = ((Number) assignments.getFirst().get("id")).longValue();
        String taskCode = jdbc.queryForObject("SELECT task_code FROM tasks WHERE id = ?", String.class, taskId);
        switch (request.eventType()) {
            case "TRANSFER" -> {
                if (nullable(request.handlerName()) == null) throw new IllegalArgumentException("转交时必须填写接收人员");
                jdbc.update("UPDATE task_equipment SET assigned_user = ?, transfer_description = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                        request.handlerName().trim(), request.description().trim(), assignmentId);
                addEvent(equipmentId, assignmentId, "TRANSFER", "IN_TASK", "IN_TASK", user.id(),
                        "转交给 " + request.handlerName().trim() + "：" + request.description().trim());
            }
            case "ANOMALY" -> addEvent(equipmentId, assignmentId, "ANOMALY", "IN_TASK", "IN_TASK", user.id(), request.description().trim());
            case "DAMAGE" -> closeForException(assignmentId, equipmentId, user, "MAINTENANCE", "DAMAGE", request.description().trim(), taskCode);
            case "LOST" -> closeForException(assignmentId, equipmentId, user, "DISABLED", "LOST", request.description().trim(), taskCode);
            default -> throw new IllegalStateException("Unexpected event type");
        }
    }

    private void closeForException(long assignmentId, long equipmentId, AppUser user, String nextStatus,
                                   String eventType, String description, String taskCode) {
        jdbc.update("""
                UPDATE task_equipment SET returned_at = CURRENT_TIMESTAMP, returned_user = ?, return_status = ?,
                    damage_description = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, user.name(), nextStatus, description, assignmentId);
        jdbc.update("""
                UPDATE equipment SET status = ?, disabled_at = CASE WHEN ? = 'DISABLED' THEN CURRENT_TIMESTAMP ELSE NULL END,
                    updated_by = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, nextStatus, nextStatus, user.id(), equipmentId);
        addEvent(equipmentId, assignmentId, eventType, "IN_TASK", nextStatus, user.id(),
                "任务 " + taskCode + "：" + description);
    }

    @Transactional
    public void complete(long taskId) {
        AppUser user = currentUser.get();
        requireTaskEditor(taskId, user);
        Long outstanding = jdbc.queryForObject("SELECT COUNT(*) FROM task_equipment WHERE task_id = ? AND returned_at IS NULL", Long.class, taskId);
        if (outstanding != null && outstanding > 0) throw new IllegalStateException("仍有 " + outstanding + " 件装备未归还，不能完成任务");
        int updated = jdbc.update("UPDATE tasks SET status = 'COMPLETED', updated_by = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND status = 'EXECUTING'", user.id(), taskId);
        if (updated == 0) throw new IllegalStateException("任务当前不可完成");
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("""
                SELECT t.id, t.task_code, t.name, t.type, t.location, t.start_time, t.end_time, t.status,
                       t.leader, t.created_by, u.name AS creator_name,
                       COUNT(te.id) AS equipment_total,
                       COUNT(te.id) FILTER (WHERE te.returned_at IS NULL) AS equipment_outstanding
                FROM tasks t JOIN users u ON u.id = t.created_by
                LEFT JOIN task_equipment te ON te.task_id = t.id
                GROUP BY t.id, u.name ORDER BY t.id DESC
                """);
    }

    public Map<String, Object> detail(long taskId) {
        List<Map<String, Object>> tasks = jdbc.queryForList("""
                SELECT t.*, u.name AS creator_name FROM tasks t JOIN users u ON u.id = t.created_by WHERE t.id = ?
                """, taskId);
        if (tasks.isEmpty()) throw new IllegalArgumentException("任务不存在");
        List<Map<String, Object>> equipment = jdbc.queryForList("""
                SELECT te.*, e.equipment_code, e.name AS equipment_name, e.status AS current_status,
                       c.name AS category_name
                FROM task_equipment te JOIN equipment e ON e.id = te.equipment_id
                JOIN equipment_categories c ON c.id = e.category_id
                WHERE te.task_id = ? ORDER BY te.id
                """, taskId);
        return Map.of("task", tasks.getFirst(), "equipment", equipment);
    }

    private void requireTaskEditor(long taskId, AppUser user) {
        List<Map<String, Object>> tasks = jdbc.queryForList("SELECT created_by, status FROM tasks WHERE id = ? FOR UPDATE", taskId);
        if (tasks.isEmpty()) throw new IllegalArgumentException("任务不存在");
        Number creator = (Number) tasks.getFirst().get("created_by");
        if (!user.isAdmin() && creator.longValue() != user.id()) throw new IllegalArgumentException("仅任务创建者或管理者可操作该任务");
        if (!"EXECUTING".equals(tasks.getFirst().get("status"))) throw new IllegalStateException("只有执行中的任务可以进行此操作");
    }

    private String nextCode() {
        Long next = jdbc.queryForObject("SELECT nextval('task_code_seq')", Long.class);
        return "TASK-%06d".formatted(next);
    }

    private void addEvent(long equipmentId, long assignmentId, String type, String oldStatus, String newStatus,
                          long operatorId, String description) {
        jdbc.update("""
                INSERT INTO equipment_events(equipment_id, task_equipment_id, event_type, old_status, new_status, operator_id, description)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, equipmentId, assignmentId, type, oldStatus, newStatus, operatorId, description);
    }

    private String nullable(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    public record CreateTaskRequest(String name, String type, String location, String startTime, String endTime,
                                    String leader, String participants, String description, List<Long> equipmentIds) { }
    public record ReturnEquipmentRequest(String returnStatus, BigDecimal mileage, String usageDescription,
                                         String damageDescription, String transferDescription, String description) { }
    public record TaskEquipmentEventRequest(String eventType, String handlerName, String description) { }
}
