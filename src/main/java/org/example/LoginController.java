package org.example;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class LoginController {

    @Autowired
    private DatabaseService db;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private JdbcTemplate jdbc;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @PostMapping("/login")
    public ResponseEntity<String> login(@RequestBody LoginRequest request, HttpServletResponse response) {

        User user = db.loadUserByLogin(request.getLogin());

        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Пользователь не найден");
        }

        if (!encoder.matches(request.getPassword(), user.getPassword())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Неверный пароль");
        }

        // Регистрация обычным логином/паролем требует подтверждения почты —
        // не пускаем, пока verified=false (Google-аккаунты сюда не попадают,
        // у них verified=TRUE по умолчанию, см. миграцию в schema.sql).
        List<Boolean> verifiedRows = jdbc.queryForList(
            "SELECT verified FROM users WHERE login = ?", Boolean.class, request.getLogin()
        );
        if (!verifiedRows.isEmpty() && Boolean.FALSE.equals(verifiedRows.get(0))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("needsVerification");
        }

        // Длительность сессии зависит от чекбокса "Запомнить меня":
        // rememberMe=true -> persistent cookie на 30 дней,
        // rememberMe=false -> session cookie, удалится при закрытии браузера.
        String token = jwtUtil.generateToken(user.getLogin(), request.isRememberMe());
        CookieUtil.setAuthCookie(response, token, request.isRememberMe());

        return ResponseEntity.ok("Вход выполнен");
    }
}