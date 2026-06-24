package org.example;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DatabaseService {

    @Autowired
    private JdbcTemplate jdbc;

    public void saveGoal(Goal goal) {
        jdbc.update(
            "INSERT INTO goals (login, type, target) VALUES (?, ?, ?)",
            goal.getLogin(), goal.getType(), goal.getTarget()
        );
        System.out.println("Цель сохранена!");
    }

    public List<Goal> loadGoals(String login) {
        return jdbc.query(
            "SELECT * FROM goals WHERE login = ?",
            (rs, rowNum) -> new Goal(
                rs.getString("type"),
                rs.getDouble("target"),
                rs.getString("login")
            ),
            login
        );
    }

    public void saveWorkouts(User user) {
        jdbc.update("DELETE FROM workouts WHERE login = ?", user.getLogin());

        for (DailyRecord record : user.getHistory()) {
            for (WorkoutStats w : record.getWorkouts()) {
                jdbc.update(
                    "INSERT INTO workouts (date, login, name, pushups, squats, hours) VALUES (?, ?, ?, ?, ?, ?)",
                    record.getDate().toString(), user.getLogin(),
                    w.getName(), w.getPushUps(), w.getSquats(), w.getHours()
                );
            }
        }
        System.out.println("Тренировки сохранены!");
    }

    public void saveStudies(User user) {
        jdbc.update("DELETE FROM studies WHERE login = ?", user.getLogin());

        for (DailyRecord record : user.getHistory()) {
            for (StudyStats s : record.getStudies()) {
                jdbc.update(
                    "INSERT INTO studies (date, login, name, javahours, englishhours, hours) VALUES (?, ?, ?, ?, ?, ?)",
                    record.getDate().toString(), user.getLogin(),
                    s.getName(), s.getJavaHours(), s.getEnglishHours(), s.getHours()
                );
            }
        }
        System.out.println("Учёба сохранена!");
    }

    public void loadHistory(User user) {
        // ─── ЗАГРУЖАЕМ ТРЕНИРОВКИ ───
        List<WorkoutRow> workoutRows = jdbc.query(
            "SELECT * FROM workouts WHERE login = ?",
            (rs, rowNum) -> new WorkoutRow(
                rs.getString("date"),
                rs.getInt("pushups"),
                rs.getInt("squats"),
                rs.getDouble("hours"),
                rs.getString("name")
            ),
            user.getLogin()
        );

        for (WorkoutRow row : workoutRows) {
            LocalDate date = LocalDate.parse(row.date);
            DailyRecord record = user.findRecord(date);
            if (record == null) {
                record = new DailyRecord(date);
                user.addRecord(record);
            }
            record.addWorkout(new WorkoutStats(row.pushUps, row.squats, row.hours, row.name));
        }

        // ─── ЗАГРУЖАЕМ УЧЁБУ ───
        List<StudyRow> studyRows = jdbc.query(
            "SELECT * FROM studies WHERE login = ?",
            (rs, rowNum) -> new StudyRow(
                rs.getString("date"),
                rs.getDouble("hours"),
                rs.getString("name"),
                rs.getDouble("javahours"),
                rs.getDouble("englishhours")
            ),
            user.getLogin()
        );

        for (StudyRow row : studyRows) {
            LocalDate date = LocalDate.parse(row.date);
            DailyRecord record = user.findRecord(date);
            if (record == null) {
                record = new DailyRecord(date);
                user.addRecord(record);
            }
            record.addStudy(new StudyStats(row.hours, row.name, row.javaHours, row.englishHours));
        }
    }

    public void saveUser(User user) {
        jdbc.update(
            "INSERT INTO users (login, password, name) VALUES (?, ?, ?) " +
            "ON CONFLICT (login) DO UPDATE SET password = EXCLUDED.password, name = EXCLUDED.name",
            user.getLogin(), user.getPassword(), user.getName()
        );
        System.out.println("Пользователь сохранён!");
    }

    public User loadUserByLogin(String login) {
        List<User> users = jdbc.query(
            "SELECT * FROM users WHERE login = ?",
            (rs, rowNum) -> new User(
                rs.getString("login"),
                rs.getString("password"),
                rs.getString("name")
            ),
            login
        );
        return users.isEmpty() ? null : users.get(0);
    }

    // ─── ВСПОМОГАТЕЛЬНЫЕ КЛАССЫ ───
    private static class WorkoutRow {
        String date, name;
        int pushUps, squats;
        double hours;
        WorkoutRow(String date, int pushUps, int squats, double hours, String name) {
            this.date = date; this.pushUps = pushUps; this.squats = squats;
            this.hours = hours; this.name = name;
        }
    }

    private static class StudyRow {
        String date, name;
        double hours, javaHours, englishHours;
        StudyRow(String date, double hours, String name, double javaHours, double englishHours) {
            this.date = date; this.hours = hours; this.name = name;
            this.javaHours = javaHours; this.englishHours = englishHours;
        }
    }
}