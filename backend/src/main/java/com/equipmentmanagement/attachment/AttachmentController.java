package com.equipmentmanagement.attachment;

import com.equipmentmanagement.auth.AppUser;
import com.equipmentmanagement.auth.CurrentUser;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/attachments")
public class AttachmentController {
    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;
    private final Path uploadRoot;

    public AttachmentController(JdbcTemplate jdbc, CurrentUser currentUser, @Value("${app.upload-dir}") String uploadDir) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.uploadRoot = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) Long equipmentId,
                                           @RequestParam(required = false) Long taskId) {
        return jdbc.queryForList("""
                SELECT id, file_name, file_type, file_size, equipment_id, task_id, event_id, uploaded_by, created_at
                FROM attachments WHERE (CAST(? AS BIGINT) IS NULL OR equipment_id = ?)
                    AND (CAST(? AS BIGINT) IS NULL OR task_id = ?) ORDER BY id DESC
                """, equipmentId, equipmentId, taskId, taskId);
    }

    @PostMapping
    public Map<String, Object> upload(@RequestParam MultipartFile file,
                                      @RequestParam(required = false) Long equipmentId,
                                      @RequestParam(required = false) Long taskId,
                                      @RequestParam(required = false) Long eventId) throws IOException {
        AppUser user = currentUser.get();
        int related = (equipmentId == null ? 0 : 1) + (taskId == null ? 0 : 1) + (eventId == null ? 0 : 1);
        if (related != 1) throw new IllegalArgumentException("附件必须关联一件装备、一个任务或一条事件");
        if (file.isEmpty()) throw new IllegalArgumentException("请选择文件");
        if (file.getSize() > 20L * 1024 * 1024) throw new IllegalArgumentException("单个附件不能超过 20MB");
        String safeName = Path.of(file.getOriginalFilename() == null ? "file" : file.getOriginalFilename()).getFileName().toString();
        String storedName = UUID.randomUUID() + "_" + safeName;
        Path targetDir = uploadRoot.resolve(LocalDate.now().toString());
        Files.createDirectories(targetDir);
        Path target = targetDir.resolve(storedName).normalize();
        if (!target.startsWith(uploadRoot)) throw new IllegalArgumentException("非法文件路径");
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        Long id = jdbc.queryForObject("""
                INSERT INTO attachments(file_name, file_path, file_type, file_size, equipment_id, task_id, event_id, uploaded_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, safeName, uploadRoot.relativize(target).toString().replace('\\', '/'), file.getContentType(),
                file.getSize(), equipmentId, taskId, eventId, user.id());
        return Map.of("id", id, "fileName", safeName);
    }

    @GetMapping("/{id}/download")
    public org.springframework.http.ResponseEntity<Resource> download(@PathVariable long id) {
        List<Map<String, Object>> files = jdbc.queryForList("SELECT file_name, file_path, file_type FROM attachments WHERE id = ?", id);
        if (files.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "附件不存在");
        Map<String, Object> metadata = files.getFirst();
        Path target = uploadRoot.resolve((String) metadata.get("file_path")).normalize();
        if (!target.startsWith(uploadRoot) || !Files.exists(target)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "附件文件不存在");
        String type = (String) metadata.get("file_type");
        String encodedName = URLEncoder.encode((String) metadata.get("file_name"), java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        return org.springframework.http.ResponseEntity.ok()
                .contentType(type == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(type))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                .body(new FileSystemResource(target));
    }
}
