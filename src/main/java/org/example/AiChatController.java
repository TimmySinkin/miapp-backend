package org.example;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/ai/chats")
public class AiChatController {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // RowMapper для строки чата (без сообщений — их подгружаем отдельно).
    private static final RowMapper<ChatDto> CHAT_ROW_MAPPER = (rs, rowNum) -> {
        ChatDto dto = new ChatDto();
        dto.id = rs.getString("id");
        dto.title = rs.getString("title");
        dto.goalText = rs.getString("goal_text");
        dto.icon = rs.getString("icon");
        dto.iconTint = rs.getString("icon_tint");
        dto.iconFg = rs.getString("icon_fg");
        dto.favorite = rs.getBoolean("favorite");
        int totalDays = rs.getInt("plan_total_days");
        dto.planTotalDays = rs.wasNull() ? null : totalDays;
        dto.planStartDate = rs.getString("plan_start_date");
        int progress = rs.getInt("plan_progress");
        dto.planProgress = rs.wasNull() ? null : progress;
        Timestamp createdAt = rs.getTimestamp("created_at");
        dto.createdAt = createdAt != null ? createdAt.toLocalDateTime().toString() : null;
        return dto;
    };

    private static final RowMapper<MessageDto> MESSAGE_ROW_MAPPER = (rs, rowNum) -> {
        MessageDto dto = new MessageDto();
        dto.role = rs.getString("role");
        dto.text = rs.getString("text");
        dto.attachmentsJson = rs.getString("attachments_json");
        Timestamp createdAt = rs.getTimestamp("created_at");
        dto.createdAt = createdAt != null ? createdAt.toLocalDateTime().toString() : null;
        return dto;
    };

    // Загружает все чаты пользователя вместе с сообщениями —
    // вызывается один раз при открытии страницы AI-агента.
    @GetMapping("/{login}")
    public ResponseEntity<List<ChatDto>> getChats(@PathVariable String login) {
        List<ChatDto> chats = jdbcTemplate.query(
            "SELECT * FROM ai_chats WHERE login = ? ORDER BY created_at DESC",
            CHAT_ROW_MAPPER,
            login
        );

        for (ChatDto chat : chats) {
            chat.messages = jdbcTemplate.query(
                "SELECT * FROM ai_messages WHERE chat_id = ? ORDER BY id ASC",
                MESSAGE_ROW_MAPPER,
                chat.id
            );

            // Прогресс плана считаем автоматически по факту закрытых дней в
            // календаре, а не берём ручное значение из БД — пользователь больше
            // не двигает слайдер сам, всё выводится из day_tasks с этим chat_id.
            if (chat.planTotalDays != null && chat.planTotalDays > 0) {
                chat.planProgress = computePlanProgress(chat.id, chat.planTotalDays);
            }
        }

        return ResponseEntity.ok(chats);
    }

    // День считается "закрытым", если у него есть хотя бы одна задача этого
    // плана (chat_id) на эту дату, и ни одна измеримая задача (goal_count > 0)
    // не осталась недовыполненной. Задачи без измеримой цели (goal_count <= 0,
    // например "день отдыха") не мешают дню засчитаться закрытым.
    // Прогресс = (число закрытых дней) / (общий срок плана) × 100.
    private Integer computePlanProgress(String chatId, int totalDays) {
        Integer completedDays = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM (" +
            "  SELECT date FROM day_tasks " +
            "  WHERE chat_id = ? " +
            "  GROUP BY date " +
            "  HAVING COUNT(*) FILTER (WHERE goal_count > 0 AND (progress IS NULL OR progress < goal_count)) = 0" +
            ") AS completed_days",
            Integer.class,
            chatId
        );
        if (completedDays == null) completedDays = 0;
        int percent = Math.round(100f * completedDays / totalDays);
        return Math.max(0, Math.min(100, percent));
    }

    // Создаёт новый чат (вызывается при отправке первого сообщения в чате).
    @PostMapping
    public ResponseEntity<Void> createChat(@RequestBody CreateChatRequest req) {
        jdbcTemplate.update(
            "INSERT INTO ai_chats (id, login, title, goal_text, icon, icon_tint, icon_fg, favorite, plan_progress, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, FALSE, 0, ?)",
            req.getId(),
            req.getLogin(),
            req.getTitle(),
            req.getGoalText(),
            req.getIcon(),
            req.getIconTint(),
            req.getIconFg(),
            Timestamp.valueOf(LocalDateTime.now())
        );
        return ResponseEntity.ok().build();
    }

    // Добавляет сообщение (пользователя или бота) в существующий чат.
    @PostMapping("/{chatId}/messages")
    public ResponseEntity<Void> addMessage(@PathVariable String chatId, @RequestBody AddMessageRequest req) {
        jdbcTemplate.update(
            "INSERT INTO ai_messages (chat_id, role, text, attachments_json, created_at) VALUES (?, ?, ?, ?, ?)",
            chatId,
            req.getRole(),
            req.getText(),
            req.getAttachmentsJson(),
            Timestamp.valueOf(LocalDateTime.now())
        );
        return ResponseEntity.ok().build();
    }

    // Переименование чата.
    @PatchMapping("/{chatId}/title")
    public ResponseEntity<Void> renameChat(@PathVariable String chatId, @RequestBody RenameRequest req) {
        int updated = jdbcTemplate.update(
            "UPDATE ai_chats SET title = ? WHERE id = ?",
            req.getTitle(),
            chatId
        );
        return updated > 0 ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    // Переключение "избранное".
    @PatchMapping("/{chatId}/favorite")
    public ResponseEntity<Void> toggleFavorite(@PathVariable String chatId) {
        int updated = jdbcTemplate.update(
            "UPDATE ai_chats SET favorite = NOT favorite WHERE id = ?",
            chatId
        );
        return updated > 0 ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    // Принятие плана (срок + дата старта) или обновление ручного прогресса.
    // Обновляем только те поля, что реально пришли в запросе.
    @PatchMapping("/{chatId}/plan")
    public ResponseEntity<Void> updatePlan(@PathVariable String chatId, @RequestBody PlanUpdateRequest req) {
        StringBuilder sql = new StringBuilder("UPDATE ai_chats SET ");
        java.util.List<Object> params = new java.util.ArrayList<>();
        java.util.List<String> sets = new java.util.ArrayList<>();

        if (req.getTotalDays() != null) {
            sets.add("plan_total_days = ?");
            params.add(req.getTotalDays());
        }
        if (req.getStartDate() != null) {
            sets.add("plan_start_date = ?");
            params.add(req.getStartDate());
        }
        if (req.getProgress() != null) {
            sets.add("plan_progress = ?");
            params.add(req.getProgress());
        }

        if (sets.isEmpty()) {
            return ResponseEntity.ok().build();
        }

        sql.append(String.join(", ", sets)).append(" WHERE id = ?");
        params.add(chatId);

        int updated = jdbcTemplate.update(sql.toString(), params.toArray());
        return updated > 0 ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    // Убрать план из активных (totalDays = NULL).
    @DeleteMapping("/{chatId}/plan")
    public ResponseEntity<Void> removePlan(@PathVariable String chatId) {
        int updated = jdbcTemplate.update(
            "UPDATE ai_chats SET plan_total_days = NULL, plan_start_date = NULL, plan_progress = 0 WHERE id = ?",
            chatId
        );
        return updated > 0 ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    // Удаление чата целиком (сообщения удалятся каскадно на уровне БД —
    // см. ON DELETE CASCADE в ai_chats_schema.sql).
    @DeleteMapping("/{chatId}")
    public ResponseEntity<Void> deleteChat(@PathVariable String chatId) {
        jdbcTemplate.update("DELETE FROM ai_chats WHERE id = ?", chatId);
        return ResponseEntity.ok().build();
    }

    // --- DTO для ответа фронту ---

    public static class ChatDto {
        public String id;
        public String title;
        public String goalText;
        public String icon;
        public String iconTint;
        public String iconFg;
        public boolean favorite;
        public Integer planTotalDays;
        public String planStartDate;
        public Integer planProgress;
        public String createdAt;
        public List<MessageDto> messages;
    }

    public static class MessageDto {
        public String role;
        public String text;
        public String attachmentsJson;
        public String createdAt;
    }

    // --- DTO для входящих запросов ---

    public static class CreateChatRequest {
        private String id;
        private String login;
        private String title;
        private String goalText;
        private String icon;
        private String iconTint;
        private String iconFg;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getLogin() { return login; }
        public void setLogin(String login) { this.login = login; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getGoalText() { return goalText; }
        public void setGoalText(String goalText) { this.goalText = goalText; }
        public String getIcon() { return icon; }
        public void setIcon(String icon) { this.icon = icon; }
        public String getIconTint() { return iconTint; }
        public void setIconTint(String iconTint) { this.iconTint = iconTint; }
        public String getIconFg() { return iconFg; }
        public void setIconFg(String iconFg) { this.iconFg = iconFg; }
    }

    public static class AddMessageRequest {
        private String role;
        private String text;
        private String attachmentsJson;

        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public String getAttachmentsJson() { return attachmentsJson; }
        public void setAttachmentsJson(String attachmentsJson) { this.attachmentsJson = attachmentsJson; }
    }

    public static class RenameRequest {
        private String title;
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
    }

    public static class PlanUpdateRequest {
        private Integer totalDays;
        private String startDate;
        private Integer progress;

        public Integer getTotalDays() { return totalDays; }
        public void setTotalDays(Integer totalDays) { this.totalDays = totalDays; }
        public String getStartDate() { return startDate; }
        public void setStartDate(String startDate) { this.startDate = startDate; }
        public Integer getProgress() { return progress; }
        public void setProgress(Integer progress) { this.progress = progress; }
    }
}