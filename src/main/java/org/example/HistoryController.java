package org.example;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class HistoryController {

    @Autowired
    private DatabaseService db;

    @GetMapping("/history/{login}")
    public List<DailyRecord> getHistory(
            @PathVariable String login) {

        User user = db.loadUserByLogin(login);

        if (user == null) {
            return List.of();
        }

        db.loadHistory(user);

        return user.getHistory();
    }
}
