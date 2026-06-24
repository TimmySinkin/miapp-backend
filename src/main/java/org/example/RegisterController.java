package org.example;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class RegisterController {

    @Autowired
    private DatabaseService db;

    @PostMapping("/register")
    public String register(@RequestBody RegisterRequest request) {
        if (db.loadUserByLogin(request.getLogin()) != null) {
            return "Пользователь уже существует!";
        }

        String hashedPassword = new BCryptPasswordEncoder().encode(request.getPassword());
        User newUser = new User(request.getLogin(), hashedPassword, request.getName());
        db.saveUser(newUser);
        return "Регистрация успешна!";
    }
}