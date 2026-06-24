package org.example;

import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    @Autowired
    private DatabaseService db;

    @GetMapping("/{login}")
    public Map<String, Object> getAnalytics(@PathVariable String login) {
        User user = db.loadUserByLogin(login);
        db.loadHistory(user);

        Map<String, Object> result = new HashMap<>();

        // ─── ТРЕНИРОВКИ ───
        int totalPushUps = user.getHistory().stream()
                .flatMap(r -> r.getWorkouts().stream())
                .mapToInt(w -> w.getPushUps())
                .sum();

        int totalSquats = user.getHistory().stream()
                .flatMap(r -> r.getWorkouts().stream())
                .mapToInt(w -> w.getSquats())
                .sum();

        double totalWorkoutHours = user.getHistory().stream()
                .flatMap(r -> r.getWorkouts().stream())
                .mapToDouble(w -> w.getHours())
                .sum();

        // ─── УЧЁБА ───
        double totalJavaHours = user.getHistory().stream()
                .flatMap(r -> r.getStudies().stream())
                .mapToDouble(s -> s.getJavaHours())
                .sum();

        double totalEnglishHours = user.getHistory().stream()
                .flatMap(r -> r.getStudies().stream())
                .mapToDouble(s -> s.getEnglishHours())
                .sum();

        double totalStudyHours = user.getHistory().stream()
                .flatMap(r -> r.getStudies().stream())
                .mapToDouble(s -> s.getHours())
                .sum();

        result.put("totalPushUps", totalPushUps);
        result.put("totalSquats", totalSquats);
        result.put("totalWorkoutHours", totalWorkoutHours);
        result.put("totalJavaHours", totalJavaHours);
        result.put("totalEnglishHours", totalEnglishHours);
        result.put("totalStudyHours", totalStudyHours);

        return result;
    }
}