package com.equipmentmanagement.common;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final JdbcTemplate jdbc;
    public DashboardController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    public Map<String, Object> summary() {
        Map<String, Object> equipment = jdbc.queryForMap("""
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE status = 'IN_STOCK') AS in_stock,
                       COUNT(*) FILTER (WHERE status = 'IN_TASK') AS in_task,
                       COUNT(*) FILTER (WHERE status = 'MAINTENANCE') AS maintenance,
                       COUNT(*) FILTER (WHERE status = 'DISABLED') AS disabled
                FROM equipment
                """);
        Map<String, Object> tasks = jdbc.queryForMap("""
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE status = 'EXECUTING') AS executing,
                       COUNT(*) FILTER (WHERE status = 'COMPLETED') AS completed
                FROM tasks
                """);
        List<Map<String, Object>> recentTasks = jdbc.queryForList("""
                SELECT id, task_code, name,
                       CASE status WHEN 'PLANNED' THEN '计划中' WHEN 'EXECUTING' THEN '执行中'
                           WHEN 'COMPLETED' THEN '已完成' WHEN 'CANCELLED' THEN '已取消' ELSE status END AS status,
                       leader, created_at FROM tasks ORDER BY created_at DESC LIMIT 6
                """);
        List<Map<String, Object>> recentEvents = jdbc.queryForList("""
                SELECT ev.id, ev.event_type,
                       CASE WHEN ev.event_type = 'UNIT_CHANGED' AND (ev.description IS NULL OR ev.description = '调整所属单位')
                            THEN '所属单位已调整' ELSE ev.description END AS description,
                       ev.occurred_at, e.equipment_code, e.name AS equipment_name
                FROM equipment_events ev JOIN equipment e ON e.id = ev.equipment_id
                ORDER BY ev.occurred_at DESC, ev.id DESC LIMIT 8
                """);
        return Map.of("equipment", equipment, "tasks", tasks, "recentTasks", recentTasks, "recentEvents", recentEvents);
    }
}
