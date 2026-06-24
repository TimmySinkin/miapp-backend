package org.example;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/studies")
public class StudyController {

    @Autowired
    private DatabaseService db;

    @GetMapping("/{login}")
    public List<StudyStats> getStudies(@PathVariable String login) {
        User user = db.loadUserByLogin(login);
        db.loadHistory(user);
        return user.getHistory().stream()
                .flatMap(r -> r.getStudies().stream())
                .toList();
    }
    @PostMapping("/{login}")
    public String addStudy(@PathVariable String login, @RequestBody StudyStats study) {
        User user = new User(login, "", login);
        db.loadHistory(user);
        user.getTodayRecord().addStudy(study);
        db.saveStudies(user);
        return "Учёба добавлена!";
    }
}

