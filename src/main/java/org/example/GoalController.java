package org.example;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

@RestController
@RequestMapping("/api/goals")
public class GoalController {

    @Autowired
    private DatabaseService db;

    @GetMapping("/{login}")
    public List<Map<String, Object>> getGoals(@PathVariable String login) {
        List<Goal> goals = db.loadGoals(login);
        User user = db.loadUserByLogin(login);
        db.loadHistory(user);

        // считаем текущий прогресс
        int totalPushUps = user.getHistory().stream()
                .flatMap(r -> r.getWorkouts().stream())
                .mapToInt(w -> w.getPushUps()).sum();

        int totalSquats = user.getHistory().stream()
                .flatMap(r -> r.getWorkouts().stream())
                .mapToInt(w -> w.getSquats()).sum();

        double totalJava = user.getHistory().stream()
                .flatMap(r -> r.getStudies().stream())
                .mapToDouble(s -> s.getJavaHours()).sum();

        double totalEnglish = user.getHistory().stream()
                .flatMap(r -> r.getStudies().stream())
                .mapToDouble(s -> s.getEnglishHours()).sum();

        return goals.stream().map(goal -> {
            Map<String, Object> result = new HashMap<>();
            result.put("type", goal.getType());
            result.put("target", goal.getTarget());

            double current = switch (goal.getType()) {
                case "PUSHUPS" -> totalPushUps;
                case "SQUATS" -> totalSquats;
                case "JAVA" -> totalJava;
                case "ENGLISH" -> totalEnglish;
                default -> 0;
            };

            result.put("current", current);
            result.put("percent", Math.min(100, (int)(current / goal.getTarget() * 100)));
            return result;
        }).toList();
    }

    @PostMapping("/{login}")
    public String addGoal(@PathVariable String login, @RequestBody Goal goal) {
        Goal newGoal = new Goal(goal.getType(), goal.getTarget(), login);
        db.saveGoal(newGoal);
        return "Цель добавлена!";
    }
}