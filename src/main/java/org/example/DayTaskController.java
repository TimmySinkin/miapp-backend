package org.example;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/tasks")
public class DayTaskController {

    @Autowired
    private JdbcTemplate jdbc;

    @GetMapping("/{login}/{date}")
    public ResponseEntity<?> getTasks(@PathVariable String login, @PathVariable String date) {
        List<DayTask> tasks = jdbc.query(
            "SELECT * FROM day_tasks WHERE login = ? AND date = ? ORDER BY position",
            (rs, rowNum) -> new DayTask(
                rs.getLong("id"),
                rs.getString("login"),
                rs.getString("date"),
                rs.getString("text"),
                rs.getObject("goal_count") != null ? rs.getDouble("goal_count") : null,
                rs.getObject("progress") != null ? rs.getDouble("progress") : null,
                rs.getInt("position"),
                rs.getString("category"),
                rs.getString("chat_id")
            ),
            login, date
        );
        return ResponseEntity.ok(tasks);
    }

    // chatId — необязательный query-параметр:
    // - если передан (сохранение плана из AI-агента) — заменяем ТОЛЬКО задачи,
    //   ранее сохранённые ЭТИМ ЖЕ чатом на эту дату, не трогая задачи других
    //   чатов и вручную добавленные — так разные планы/цели спокойно
    //   сосуществуют на одной дате, а повторное сохранение того же чата
    //   корректно заменяет его же прошлую версию плана, а не дублирует её.
    // - если НЕ передан (ручное сохранение из Home.jsx) — заменяем только
    //   вручную добавленные задачи (chat_id IS NULL), не трогая задачи из
    //   планов AI-агента на этот день.
    @PostMapping("/{login}/{date}")
    public ResponseEntity<String> saveTasks(
        @PathVariable String login,
        @PathVariable String date,
        @RequestParam(required = false) String chatId,
        @RequestBody List<DayTask> tasks
    ) {
        if (chatId != null && !chatId.isBlank()) {
            jdbc.update("DELETE FROM day_tasks WHERE login = ? AND date = ? AND chat_id = ?", login, date, chatId);
        } else {
            jdbc.update("DELETE FROM day_tasks WHERE login = ? AND date = ? AND chat_id IS NULL", login, date);
        }

        // Позиции нумеруем от текущего максимума на эту дату, чтобы новые задачи
        // визуально не вклинивались перед уже существующими (из другого источника).
        Integer maxPosition = jdbc.queryForObject(
            "SELECT COALESCE(MAX(position), -1) FROM day_tasks WHERE login = ? AND date = ?",
            Integer.class, login, date
        );
        int position = (maxPosition == null ? -1 : maxPosition) + 1;

        for (DayTask task : tasks) {
            jdbc.update(
                "INSERT INTO day_tasks (login, date, text, goal_count, progress, position, category, chat_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                login, date, task.getText(), task.getGoalCount(), task.getProgress(), position,
                task.getCategory() != null ? task.getCategory() : "tasks",
                (chatId != null && !chatId.isBlank()) ? chatId : null
            );
            position++;
        }
        return ResponseEntity.ok("Сохранено!");
    }
}