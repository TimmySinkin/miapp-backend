package org.example;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

@RestController
@RequestMapping("/api/stats")
public class StatsController {

    @Autowired
    private JdbcTemplate jdbc;

    @GetMapping("/{login}")
    public ResponseEntity<Map<String, Object>> getStats(@PathVariable String login) {
        Map<String, Object> stats = new HashMap<>();

        // Текущие год/месяц по времени сервера — фронт использовал new Date()
        // (локальное время браузера) для выбора "текущего месяца" из monthly[],
        // что могло разъезжаться с тем, что реально агрегировано на бэке
        // в приграничные часы месяца при разнице часовых поясов.
        LocalDate serverNow = LocalDate.now();
        stats.put("currentYear", serverNow.getYear());
        stats.put("currentMonth", serverNow.getMonthValue());

        // Задачи по месяцам — сколько задач и сколько выполнено
        List<Map<String, Object>> monthly = jdbc.queryForList(
            "SELECT " +
            "  EXTRACT(MONTH FROM date::date) as month, " +
            "  COUNT(*) as total, " +
            "  SUM(CASE WHEN progress >= goal_count AND goal_count > 0 THEN 1 ELSE 0 END) as completed " +
            "FROM day_tasks " +
            "WHERE login = ? AND date >= ? " +
            "GROUP BY EXTRACT(MONTH FROM date::date) " +
            "ORDER BY month",
            login,
            new java.sql.Date(new java.util.Calendar.Builder()
                .setDate(java.time.LocalDate.now().getYear(), 0, 1).build().getTimeInMillis())
        );
        stats.put("monthly", monthly);

        // Общее число задач
        Integer totalTasks = jdbc.queryForObject(
            "SELECT COUNT(*) FROM day_tasks WHERE login = ?", Integer.class, login);
        stats.put("totalTasks", totalTasks);

        // Выполненных задач
        Integer completedTasks = jdbc.queryForObject(
            "SELECT COUNT(*) FROM day_tasks WHERE login = ? AND progress >= goal_count AND goal_count > 0",
            Integer.class, login);
        stats.put("completedTasks", completedTasks);

        // Активных дней (дней с хотя бы одной задачей)
        Integer activeDays = jdbc.queryForObject(
            "SELECT COUNT(DISTINCT date) FROM day_tasks WHERE login = ?",
            Integer.class, login);
        stats.put("activeDays", activeDays);

        // Самый активный месяц
        // Группируем по (год, месяц) вместе, а не только по месяцу — иначе
        // "Август" за 2025 и 2026 годы схлопнутся в одну группу и результат
        // будет врать, как только у пользователя накопятся данные за второй год.
        List<Map<String, Object>> bestMonth = jdbc.queryForList(
            "SELECT EXTRACT(YEAR FROM date::date) as year, EXTRACT(MONTH FROM date::date) as month, COUNT(*) as total " +
            "FROM day_tasks WHERE login = ? " +
            "GROUP BY year, month ORDER BY total DESC LIMIT 1",
            login);
        stats.put("bestMonth", bestMonth.isEmpty() ? null : bestMonth.get(0));

        // Топ-3 самых частых действий — сумма фактически выполненных повторений (progress), а не число дней
        List<Map<String, Object>> topActions = jdbc.queryForList(
            "SELECT text, SUM(COALESCE(progress, 0)) as count FROM day_tasks WHERE login = ? " +
            "GROUP BY text ORDER BY count DESC LIMIT 3",
            login);
        stats.put("topActions", topActions);

        // Топ-5 действий ЗА ТЕКУЩИЙ ГОД — отдельно от topActions (тот — за всё время).
        // Нужно для годового отчёта (см. StatsReport): "топ действий за год" — это не то
        // же самое, что "топ действий за всё время", особенно у пользователей, которые
        // сменили фокус (например, с английского языка на программирование).
        List<Map<String, Object>> topActionsYear = jdbc.queryForList(
            "SELECT text, SUM(COALESCE(progress, 0)) as count FROM day_tasks " +
            "WHERE login = ? AND EXTRACT(YEAR FROM date::date) = ? " +
            "GROUP BY text ORDER BY count DESC LIMIT 5",
            login, LocalDate.now().getYear());
        stats.put("topActionsYear", topActionsYear);

        // ─── Сырые записи для weekly / distribution ───
        // Тянем всё разом одним запросом и агрегируем в Java —
        // так проще собрать "неделя по понедельникам" и "месяц по категориям",
        // чем городить это на SQL.
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT date, category, progress, goal_count FROM day_tasks WHERE login = ?",
            login);

        // weekStart (понедельник, "YYYY-MM-DD") -> массив из 7 {tasksTotal,tasksDone, goalsTotal,goalsDone, leisureTotal,leisureDone}
        Map<String, int[][]> weeklyMap = new LinkedHashMap<>();
        // "YYYY-MM" -> {tasks, goals, leisure}
        Map<String, int[]> distributionMap = new LinkedHashMap<>();

        for (Map<String, Object> row : rows) {
            Object dateObj = row.get("date");
            if (dateObj == null) continue;
            LocalDate date = (dateObj instanceof java.sql.Date)
                ? ((java.sql.Date) dateObj).toLocalDate()
                : LocalDate.parse(dateObj.toString());

            String category = String.valueOf(row.get("category"));
            int catIndex; // 0=tasks, 1=goals, 2=leisure, -1=неизвестно
            switch (category == null ? "" : category) {
                case "tasks": catIndex = 0; break;
                case "goals": catIndex = 1; break;
                case "leisure": catIndex = 2; break;
                default: catIndex = -1; break;
            }
            if (catIndex == -1) continue; // неизвестная категория — не учитываем ни в графике, ни в бублике

            Number progressNum = (Number) row.get("progress");
            Number goalNum = (Number) row.get("goal_count");
            double progress = progressNum == null ? 0 : progressNum.doubleValue();
            double goal = goalNum == null ? 0 : goalNum.doubleValue();
            boolean completed = goal > 0 && progress >= goal;

            // --- weekly ---
            LocalDate monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            String weekKey = monday.toString(); // ISO "YYYY-MM-DD"
            // [dayIndex][0=tasksTotal,1=tasksDone,2=goalsTotal,3=goalsDone,4=leisureTotal,5=leisureDone]
            int[][] week = weeklyMap.computeIfAbsent(weekKey, k -> new int[7][6]);
            int dayIndex = date.getDayOfWeek().getValue() - 1; // Пн=0 ... Вс=6
            week[dayIndex][catIndex * 2] += 1;
            if (completed) week[dayIndex][catIndex * 2 + 1] += 1;

            // --- distribution ---
            String monthKey = String.format("%04d-%02d", date.getYear(), date.getMonthValue());
            int[] dist = distributionMap.computeIfAbsent(monthKey, k -> new int[3]); // [tasks, goals, leisure]
            dist[catIndex] += 1;
        }

        List<Map<String, Object>> weekly = new ArrayList<>();
        for (Map.Entry<String, int[][]> e : weeklyMap.entrySet()) {
            Map<String, Object> w = new HashMap<>();
            w.put("weekStart", e.getKey());
            List<Map<String, Object>> days = new ArrayList<>();
            for (int[] d : e.getValue()) {
                Map<String, Object> dayObj = new HashMap<>();
                dayObj.put("tasksTotal", d[0]);
                dayObj.put("tasksDone", d[1]);
                dayObj.put("goalsTotal", d[2]);
                dayObj.put("goalsDone", d[3]);
                dayObj.put("leisureTotal", d[4]);
                dayObj.put("leisureDone", d[5]);
                days.add(dayObj);
            }
            w.put("days", days);
            weekly.add(w);
        }
        stats.put("weekly", weekly);

        List<Map<String, Object>> distribution = new ArrayList<>();
        for (Map.Entry<String, int[]> e : distributionMap.entrySet()) {
            Map<String, Object> d = new HashMap<>();
            d.put("month", e.getKey());
            d.put("tasks", e.getValue()[0]);
            d.put("goals", e.getValue()[1]);
            d.put("leisure", e.getValue()[2]);
            distribution.add(d);
        }
        stats.put("distribution", distribution);

        return ResponseEntity.ok(stats);
    }
}