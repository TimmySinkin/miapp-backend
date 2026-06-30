package org.example;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class RegisterController {

    @Autowired
    private DatabaseService db;

    @PostMapping("/register")
    public ResponseEntity<String> register(@RequestBody RegisterRequest request) {
        if (db.loadUserByLogin(request.getLogin()) != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("Пользователь уже существует!");
        }

        if (request.getEmail() != null && !request.getEmail().isBlank()
                && db.loadUserByEmail(request.getEmail()) != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("Этот email уже зарегистрирован!");
        }

        String hashedPassword = new BCryptPasswordEncoder().encode(request.getPassword());
        User newUser = new User(request.getLogin(), hashedPassword, request.getName(), request.getEmail());
        db.saveUser(newUser);
        return ResponseEntity.ok("Регистрация успешна!");
    }
}