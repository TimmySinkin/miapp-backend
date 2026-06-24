package org.example;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/workouts")
public class WorkoutController {

    @Autowired
    private DatabaseService db;

    @GetMapping("/{login}")
    public List<WorkoutStats> getWorkouts(@PathVariable String login) {
        User user = db.loadUserByLogin(login);
        db.loadHistory(user);
        return user.getHistory().stream()
                .flatMap(r -> r.getWorkouts().stream())
                .toList();
    }
    @PostMapping("/{login}")
    public String addWorkout(@PathVariable String login, @RequestBody WorkoutStats workout) {
        User user = new User(login, "", login);
        db.loadHistory(user);
        user.getTodayRecord().addWorkout(workout);
        db.saveWorkouts(user);
        return "Тренировка добавлена!";
    }
    @GetMapping("/me")
    public String getCurrentLogin(java.security.Principal principal) {
        return principal.getName();
    }
}