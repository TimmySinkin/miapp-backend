package org.example;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/password-reset")
public class PasswordResetController {

    private final JdbcTemplate jdbc;
    private final MailService mailService;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    private static final int CODE_TTL_MINUTES = 10;

    public PasswordResetController(JdbcTemplate jdbc, MailService mailService) {
        this.jdbc = jdbc;
        this.mailService = mailService;
    }

    private String generateCode() {
        return String.format("%04d", random.nextInt(10000));
    }

    /**
     * Принимает логин ИЛИ email в одном поле "identifier" — пользователь может
     * не помнить, что вводил при регистрации. Google-аккаунты без пароля
     * (password IS NULL) через эту форму не сбросить — у них нет пароля,
     * который можно было бы сбросить, только вход через Google.
     */
    @PostMapping("/request")
    public ResponseEntity<String> request(@RequestBody Map<String, String> body) {
        String identifier = body.get("identifier");
        if (identifier == null || identifier.isBlank()) {
            return ResponseEntity.badRequest().body("Введите логин или email");
        }

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT login, email, password FROM users WHERE login = ? OR email = ?",
            identifier, identifier
        );

        // Намеренно не сообщаем, найден ли аккаунт — иначе через эту форму
        // можно перебором узнавать, какие логины/email зарегистрированы.
        // С точки зрения пользователя ответ всегда один и тот же.
        String genericOk = "Если такой аккаунт существует, на почту отправлен код";

        if (rows.isEmpty()) {
            return ResponseEntity.ok(genericOk);
        }
        Map<String, Object> row = rows.get(0);
        String login = (String) row.get("login");
        String email = (String) row.get("email");
        Object password = row.get("password");

        if (email == null || email.isBlank()) {
            return ResponseEntity.ok(genericOk);
        }
        if (password == null) {
            return ResponseEntity.ok(genericOk);
        }

        String code = generateCode();
        Timestamp expires = Timestamp.valueOf(LocalDateTime.now().plusMinutes(CODE_TTL_MINUTES));
        jdbc.update("UPDATE users SET reset_code = ?, reset_expires = ? WHERE login = ?", code, expires, login);

        try {
            mailService.sendPasswordResetCode(email, code);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body("Не удалось отправить письмо, попробуйте позже");
        }

        return ResponseEntity.ok(genericOk);
    }

    @PostMapping("/confirm")
    public ResponseEntity<String> confirm(@RequestBody Map<String, String> body) {
        String identifier = body.get("identifier");
        String code = body.get("code");
        String newPassword = body.get("newPassword");

        if (identifier == null || identifier.isBlank() || code == null || newPassword == null) {
            return ResponseEntity.badRequest().body("Заполните все поля");
        }

        String passwordError = PasswordValidator.validate(newPassword);
        if (passwordError != null) {
            return ResponseEntity.badRequest().body(passwordError);
        }

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT login, reset_code, reset_expires FROM users WHERE login = ? OR email = ?",
            identifier, identifier
        );
        if (rows.isEmpty()) {
            return ResponseEntity.status(404).body("Код неверный или устарел, запросите новый");
        }
        Map<String, Object> row = rows.get(0);
        String login = (String) row.get("login");
        String storedCode = (String) row.get("reset_code");
        Timestamp expires = (Timestamp) row.get("reset_expires");

        if (storedCode == null || expires == null || !storedCode.equals(code)
                || expires.toLocalDateTime().isBefore(LocalDateTime.now())) {
            return ResponseEntity.status(401).body("Код неверный или устарел, запросите новый");
        }

        String hashed = encoder.encode(newPassword);
        jdbc.update(
            "UPDATE users SET password = ?, reset_code = NULL, reset_expires = NULL WHERE login = ?",
            hashed, login
        );

        return ResponseEntity.ok("Пароль обновлён, теперь можно войти");
    }
}
