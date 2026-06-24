package org.example;

import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class DatabaseService {

    private static final String URL = "jdbc:sqlite:miniapp.db";

    // ─── СОЗДАНИЕ ТАБЛИЦ ───
    public void initDatabase() {
        try (Connection conn = DriverManager.getConnection(URL);
             Statement stmt = conn.createStatement()) {

            stmt.execute(
                    "CREATE TABLE IF NOT EXISTS users (" +
                            "login TEXT PRIMARY KEY," +
                            "password TEXT," +
                            "name TEXT" +
                            ")"
            );

            stmt.execute(
                    "CREATE TABLE IF NOT EXISTS workouts (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                            "date TEXT," +
                            "login TEXT," +
                            "name TEXT," +
                            "pushUps INTEGER," +
                            "squats INTEGER," +
                            "hours REAL" +
                            ")"
            );

            stmt.execute(
                    "CREATE TABLE IF NOT EXISTS studies (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                            "date TEXT," +
                            "login TEXT," +
                            "name TEXT," +
                            "javaHours REAL," +
                            "englishHours REAL," +
                            "hours REAL" +
                            ")"
            );

            stmt.execute(
                    "CREATE TABLE IF NOT EXISTS goals (" +
                            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                            "login TEXT," +
                            "type TEXT," +
                            "target REAL" +
                            ")"
            );

        } catch (SQLException e) {
            System.out.println("Ошибка базы данных: " + e.getMessage());
        }
    }

    public void saveGoal(Goal goal) {
        try (Connection conn = DriverManager.getConnection(URL);
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO goals (login, type, target) VALUES (?, ?, ?)"
             )) {

            stmt.setString(1, goal.getLogin());
            stmt.setString(2, goal.getType());
            stmt.setDouble(3, goal.getTarget());
            stmt.execute();
            System.out.println("Цель сохранена!");

        } catch (SQLException e) {
            System.out.println("Ошибка сохранения цели: " + e.getMessage());
        }
    }

    public List<Goal> loadGoals(String login) {
        List<Goal> goals = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(URL);
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT * FROM goals WHERE login = ?"
             )) {

            stmt.setString(1, login);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                goals.add(new Goal(
                        rs.getString("type"),
                        rs.getDouble("target"),
                        rs.getString("login")
                ));
            }

        } catch (SQLException e) {
            System.out.println("Ошибка загрузки целей: " + e.getMessage());
        }
        return goals;
    }

    public void saveWorkouts(User user) {
        try (Connection conn = DriverManager.getConnection(URL);
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO workouts (date, login, name, pushUps, squats, hours) VALUES (?, ?, ?, ?, ?, ?)"
             )) {

            PreparedStatement delete = conn.prepareStatement(
                    "DELETE FROM workouts WHERE login = ?"
            );
            delete.setString(1, user.getLogin());
            delete.execute();

            for (DailyRecord record : user.getHistory()) {
                for (WorkoutStats w : record.getWorkouts()) {
                    stmt.setString(1, record.getDate().toString());
                    stmt.setString(2, user.getLogin());
                    stmt.setString(3, w.getName());
                    stmt.setInt(4, w.getPushUps());
                    stmt.setInt(5, w.getSquats());
                    stmt.setDouble(6, w.getHours());
                    stmt.execute();
                }
            }

            System.out.println("Тренировки сохранены!");

        } catch (SQLException e) {
            System.out.println("Ошибка сохранения: " + e.getMessage());
        }
    }

    public void saveStudies(User user) {
        try (Connection conn = DriverManager.getConnection(URL);
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO studies (date, login, name, javaHours, englishHours, hours) VALUES (?, ?, ?, ?, ?, ?)"
             )) {

            PreparedStatement delete = conn.prepareStatement(
                    "DELETE FROM studies WHERE login = ?"
            );
            delete.setString(1, user.getLogin());
            delete.execute();

            for (DailyRecord record : user.getHistory()) {
                for (StudyStats s : record.getStudies()) {
                    stmt.setString(1, record.getDate().toString());
                    stmt.setString(2, user.getLogin());
                    stmt.setString(3, s.getName());
                    stmt.setDouble(4, s.getJavaHours());
                    stmt.setDouble(5, s.getEnglishHours());
                    stmt.setDouble(6, s.getHours());
                    stmt.execute();
                }
            }

            System.out.println("Учёба сохранена!");

        } catch (SQLException e) {
            System.out.println("Ошибка сохранения: " + e.getMessage());
        }
    }

    public void loadHistory(User user) {
        try (Connection conn = DriverManager.getConnection(URL)) {

            // ─── ЗАГРУЖАЕМ ТРЕНИРОВКИ ───
            PreparedStatement workoutStmt = conn.prepareStatement(
                    "SELECT * FROM workouts WHERE login = ?"
            );
            workoutStmt.setString(1, user.getLogin());
            ResultSet workouts = workoutStmt.executeQuery();

            while (workouts.next()) {
                LocalDate date = LocalDate.parse(workouts.getString("date"));
                DailyRecord record = user.findRecord(date);
                if (record == null) {
                    record = new DailyRecord(date);
                    user.addRecord(record);
                }
                record.addWorkout(new WorkoutStats(
                        workouts.getInt("pushUps"),
                        workouts.getInt("squats"),
                        workouts.getDouble("hours"),
                        workouts.getString("name")
                ));
            }

            // ─── ЗАГРУЖАЕМ УЧЁБУ ───
            PreparedStatement studyStmt = conn.prepareStatement(
                    "SELECT * FROM studies WHERE login = ?"
            );
            studyStmt.setString(1, user.getLogin());
            ResultSet studies = studyStmt.executeQuery();

            while (studies.next()) {
                LocalDate date = LocalDate.parse(studies.getString("date"));
                DailyRecord record = user.findRecord(date);
                if (record == null) {
                    record = new DailyRecord(date);
                    user.addRecord(record);
                }
                record.addStudy(new StudyStats(
                        studies.getDouble("hours"),
                        studies.getString("name"),
                        studies.getDouble("javaHours"),
                        studies.getDouble("englishHours")
                ));
            }

        } catch (SQLException e) {
            System.out.println("Ошибка загрузки: " + e.getMessage());
        }
    }

    public void saveUser(User user) {
        try (Connection conn = DriverManager.getConnection(URL);
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT OR REPLACE INTO users VALUES (?, ?, ?)"
             )) {

            stmt.setString(1, user.getLogin());
            stmt.setString(2, user.getPassword());
            stmt.setString(3, user.getName());
            stmt.execute();

            System.out.println("Пользователь сохранён!");

        } catch (SQLException e) {
            System.out.println("Ошибка сохранения: " + e.getMessage());
        }
    }
    public User loadUserByLogin(String login) {
        try (Connection conn = DriverManager.getConnection(URL);
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT * FROM users WHERE login = ?"
             )) {

            stmt.setString(1, login);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                return new User(
                        rs.getString("login"),
                        rs.getString("password"),
                        rs.getString("name")
                );
            }
            return null;

        } catch (SQLException e) {
            System.out.println("Ошибка: " + e.getMessage());
            return null;
        }
    }
}