package org.example;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/tasks")
public class DayTaskController {

    @Autowired
    private JdbcTemplate jdbc;

    // Получить все задачи на конкретный день
    @GetMapping("/{login}/{date}")
    public List<DayTask> getTasks(@PathVariable String login, @PathVariable String date) {
        return jdbc.query(
            "SELECT * FROM day_tasks WHERE login = ? AND date = ? ORDER BY position",
            (rs, rowNum) -> new DayTask(
                rs.getLong("id"),
                rs.getString("login"),
                rs.getString("date"),
                rs.getString("text"),
                rs.getObject("goal_count") != null ? rs.getDouble("goal_count") : null,
                rs.getObject("progress") != null ? rs.getDouble("progress") : null,
                rs.getInt("position")
            ),
            login, date
        );
    }

    // Сохранить все задачи дня (удаляет старые и вставляет новые)
    @PostMapping("/{login}/{date}")
    public String saveTasks(@PathVariable String login, @PathVariable String date, @RequestBody List<DayTask> tasks) {
        jdbc.update("DELETE FROM day_tasks WHERE login = ? AND date = ?", login, date);

        int position = 0;
        for (DayTask task : tasks) {
            jdbc.update(
                "INSERT INTO day_tasks (login, date, text, goal_count, progress, position) VALUES (?, ?, ?, ?, ?, ?)",
                login, date, task.getText(), task.getGoalCount(), task.getProgress(), position
            );
            position++;
        }
        return "Сохранено!";
    }
}
