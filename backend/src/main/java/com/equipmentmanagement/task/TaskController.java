package com.equipmentmanagement.task;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final TaskService taskService;
    public TaskController(TaskService taskService) { this.taskService = taskService; }

    @GetMapping public List<Map<String, Object>> list() { return taskService.list(); }
    @GetMapping("/{id}") public Map<String, Object> detail(@PathVariable long id) { return taskService.detail(id); }
    @PostMapping public Map<String, Object> create(@Valid @RequestBody CreateRequest request) {
        return taskService.create(new TaskService.CreateTaskRequest(request.name(), request.type(), request.location(), request.startTime(), request.endTime(), request.leader(), request.participants(), request.description(), request.equipmentIds()));
    }
    @PostMapping("/{taskId}/equipment/{equipmentId}/return") public void returnEquipment(@PathVariable long taskId, @PathVariable long equipmentId, @Valid @RequestBody ReturnRequest request) {
        taskService.returnEquipment(taskId, equipmentId, new TaskService.ReturnEquipmentRequest(request.returnStatus(), request.mileage(), request.usageDescription(), request.damageDescription(), request.transferDescription(), request.description()));
    }
    @PostMapping("/{taskId}/equipment/{equipmentId}/events") public void recordEvent(@PathVariable long taskId, @PathVariable long equipmentId, @Valid @RequestBody EventRequest request) {
        taskService.recordEquipmentEvent(taskId, equipmentId, new TaskService.TaskEquipmentEventRequest(request.eventType(), request.handlerName(), request.description()));
    }
    @PostMapping("/{id}/complete") public void complete(@PathVariable long id) { taskService.complete(id); }

    public record CreateRequest(@NotBlank String name, String type, String location, String startTime, String endTime,
                                String leader, String participants, String description, List<Long> equipmentIds) { }
    public record ReturnRequest(@NotBlank String returnStatus, BigDecimal mileage, String usageDescription,
                                String damageDescription, String transferDescription, String description) { }
    public record EventRequest(@NotBlank String eventType, String handlerName, @NotBlank String description) { }
}
