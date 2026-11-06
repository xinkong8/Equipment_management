package com.equipmentmanagement.export;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/export")
public class ExportController {
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private final JdbcTemplate jdbc;
    public ExportController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping(value = "/equipment", produces = XLSX)
    public void equipment(HttpServletResponse response) throws IOException {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT e.equipment_code, e.name, c.name AS category, e.status, e.location, e.department,
                       e.attributes::text AS attributes, e.created_at
                FROM equipment e JOIN equipment_categories c ON c.id = e.category_id ORDER BY e.id DESC
                """);
        write(response, "equipment.xlsx", List.of("装备编号", "装备名称", "类别", "状态", "地点", "部门", "动态参数", "创建时间"), rows,
                List.of("equipment_code", "name", "category", "status", "location", "department", "attributes", "created_at"));
    }

    @GetMapping(value = "/tasks", produces = XLSX)
    public void tasks(HttpServletResponse response) throws IOException {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT t.task_code, t.name, t.type, t.status, t.location, t.leader, t.start_time, t.end_time,
                       COUNT(te.id) AS equipment_total, COUNT(te.id) FILTER (WHERE te.returned_at IS NULL) AS outstanding
                FROM tasks t LEFT JOIN task_equipment te ON te.task_id = t.id GROUP BY t.id ORDER BY t.id DESC
                """);
        write(response, "tasks.xlsx", List.of("任务编号", "任务名称", "类型", "状态", "地点", "负责人", "开始时间", "结束时间", "装备总数", "未归还数"), rows,
                List.of("task_code", "name", "type", "status", "location", "leader", "start_time", "end_time", "equipment_total", "outstanding"));
    }

    @GetMapping(value = "/history", produces = XLSX)
    public void history(HttpServletResponse response) throws IOException {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT ev.occurred_at, e.equipment_code, e.name AS equipment_name, t.task_code, ev.event_type,
                       ev.old_status, ev.new_status, u.name AS operator_name, ev.description
                FROM equipment_events ev JOIN equipment e ON e.id = ev.equipment_id
                LEFT JOIN task_equipment te ON te.id = ev.task_equipment_id LEFT JOIN tasks t ON t.id = te.task_id
                LEFT JOIN users u ON u.id = ev.operator_id ORDER BY ev.occurred_at DESC, ev.id DESC
                """);
        write(response, "equipment-history.xlsx", List.of("发生时间", "装备编号", "装备名称", "任务编号", "事件", "原状态", "新状态", "操作人", "说明"), rows,
                List.of("occurred_at", "equipment_code", "equipment_name", "task_code", "event_type", "old_status", "new_status", "operator_name", "description"));
    }

    private void write(HttpServletResponse response, String filename, List<String> headers, List<Map<String, Object>> rows, List<String> keys) throws IOException {
        response.setContentType(XLSX);
        response.setHeader("Content-Disposition", "attachment; filename=" + filename);
        try (ZipOutputStream zip = new ZipOutputStream(response.getOutputStream(), StandardCharsets.UTF_8)) {
            entry(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>");
            entry(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            entry(zip, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"导出数据\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            entry(zip, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>");
            entry(zip, "xl/styles.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Arial\"/></font><font><b/><sz val=\"11\"/><name val=\"Arial\"/></font></fonts><fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills><borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count=\"1\"><xf/></cellStyleXfs><cellXfs count=\"2\"><xf xfId=\"0\"/><xf xfId=\"0\" applyFont=\"1\" fontId=\"1\"/></cellXfs></styleSheet>");
            StringBuilder sheet = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
            appendRow(sheet, 1, headers, true);
            for (int i = 0; i < rows.size(); i++) {
                Map<String, Object> row = rows.get(i);
                appendRow(sheet, i + 2, keys.stream().map(key -> value(row.get(key))).toList(), false);
            }
            sheet.append("</sheetData></worksheet>");
            entry(zip, "xl/worksheets/sheet1.xml", sheet.toString());
        }
    }

    private void entry(ZipOutputStream zip, String name, String content) throws IOException { zip.putNextEntry(new ZipEntry(name)); zip.write(content.getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); }
    private void appendRow(StringBuilder sheet, int number, List<String> values, boolean header) {
        sheet.append("<row r=\"").append(number).append("\">");
        for (int i = 0; i < values.size(); i++) {
            sheet.append("<c r=\"").append(column(i + 1)).append(number).append("\" t=\"inlineStr\"");
            if (header) sheet.append(" s=\"1\"");
            sheet.append("><is><t xml:space=\"preserve\">").append(xml(values.get(i))).append("</t></is></c>");
        }
        sheet.append("</row>");
    }
    private String column(int number) { StringBuilder result = new StringBuilder(); while (number > 0) { number--; result.insert(0, (char) ('A' + number % 26)); number /= 26; } return result.toString(); }
    private String value(Object value) { String text = value == null ? "" : String.valueOf(value); return text.startsWith("=") || text.startsWith("+") || text.startsWith("-") || text.startsWith("@") ? "'" + text : text; }
    private String xml(String text) { return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;"); }
}
