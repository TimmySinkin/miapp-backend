package org.example;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class LoginController {

    @Autowired
    private DatabaseService db;

    private final BCryptPasswordEncoder encoder =
            new BCryptPasswordEncoder();

    @PostMapping("/login")
    public String login(@RequestBody LoginRequest request) {

        User user = db.loadUserByLogin(request.getLogin());

        if (user == null) {
            return "Пользователь не найден";
        }

        if (!encoder.matches(
                request.getPassword(),
                user.getPassword())) {

            return "Неверный пароль";
        }

        return "Вход выполнен";
    }
}
