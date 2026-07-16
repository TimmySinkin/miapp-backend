package org.example;

import jakarta.servlet.http.HttpServletResponse;
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
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api")
public class RegisterController {

    private final DatabaseService db;
    private final JdbcTemplate jdbc;
    private final MailService mailService;
    private final JwtUtil jwtUtil;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    // Только латиница/цифры/спецсимволы (никакой кириллицы), от 6 символов,
    // начинается с заглавной латинской буквы, дальше хотя бы одна цифра
    // или спецсимвол где-то в пароле. Пробел не входит в разрешённые символы.
    private static final Pattern PASSWORD_ALLOWED_CHARS =
        Pattern.compile("^[A-Za-z0-9!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?~`]{6,}$");
    private static final Pattern PASSWORD_HAS_DIGIT_OR_SPECIAL =
        Pattern.compile("[0-9!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?~`]");
    private static final Pattern EMAIL_PATTERN =
        Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private static final int CODE_TTL_MINUTES = 10;

    public RegisterController(DatabaseService db, JdbcTemplate jdbc, MailService mailService, JwtUtil jwtUtil) {
        this.db = db;
        this.jdbc = jdbc;
        this.mailService = mailService;
        this.jwtUtil = jwtUtil;
    }

    /**
     * Возвращает текст первой нарушенной проверки пароля, либо null если пароль ок.
     * Тот же набор правил дублирует фронтовый чеклист под полем пароля —
     * фронт только для UX, финальное решение всегда за бэком.
     */
    private String validatePassword(String password) {
        if (password == null || password.length() < 6) {
            return "Пароль должен содержать минимум 6 символов";
        }
        char first = password.charAt(0);
        if (first < 'A' || first > 'Z') {
            return "Пароль должен начинаться с заглавной латинской буквы";
        }
        if (!PASSWORD_ALLOWED_CHARS.matcher(password).matches()) {
            return "Пароль может содержать только латинские буквы, цифры и спецсимволы (без кириллицы и пробелов)";
        }
        if (!PASSWORD_HAS_DIGIT_OR_SPECIAL.matcher(password).find()) {
            return "Пароль должен содержать цифру или спецсимвол";
        }
        return null;
    }

    private String generateCode() {
        return String.format("%04d", random.nextInt(10000));
    }

    @PostMapping("/register")
    public ResponseEntity<String> register(@RequestBody RegisterRequest request) {
        String login = request.getLogin();
        String email = request.getEmail();
        String password = request.getPassword();

        if (login == null || login.isBlank()) {
            return ResponseEntity.badRequest().body("Введите логин");
        }
        if (email == null || email.isBlank() || !EMAIL_PATTERN.matcher(email).matches()) {
            return ResponseEntity.badRequest().body("Введите корректный email — на него придёт код подтверждения");
        }
        String passwordError = validatePassword(password);
        if (passwordError != null) {
            return ResponseEntity.badRequest().body(passwordError);
        }

        // Уже есть подтверждённый пользователь с таким логином — обычный конфликт.
        User existingByLogin = db.loadUserByLogin(login);
        if (existingByLogin != null) {
            Boolean verified = isVerified(login);
            if (Boolean.TRUE.equals(verified)) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body("Пользователь уже существует!");
            }
            // Неподтверждённая попытка регистрации существует.
            if (!isExpired(login)) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    "На этот логин уже отправлен код подтверждения — проверьте почту или запросите новый код"
                );
            }
            // Код истёк и не подтверждён — освобождаем логин, регистрируем заново.
            jdbc.update("DELETE FROM users WHERE login = ?", login);
        }

        List<String> byEmailLogin = jdbc.queryForList("SELECT login FROM users WHERE email = ?", String.class, email);
        if (!byEmailLogin.isEmpty()) {
            String existingLogin = byEmailLogin.get(0);
            Boolean verified = isVerified(existingLogin);
            if (Boolean.TRUE.equals(verified)) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body("Этот email уже зарегистрирован!");
            }
            if (!isExpired(existingLogin)) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    "На этот email уже отправлен код подтверждения — проверьте почту или запросите новый код"
                );
            }
            jdbc.update("DELETE FROM users WHERE login = ?", existingLogin);
        }

        String hashedPassword = encoder.encode(password);
        User newUser = new User(login, hashedPassword, login, email);
        db.saveUser(newUser);

        String code = generateCode();
        Timestamp expires = Timestamp.valueOf(LocalDateTime.now().plusMinutes(CODE_TTL_MINUTES));
        jdbc.update(
            "UPDATE users SET verified = FALSE, verification_code = ?, verification_expires = ? WHERE login = ?",
            code, expires, login
        );

        try {
            mailService.sendVerificationCode(email, code);
        } catch (Exception e) {
            // Не удалось отправить письмо — не оставляем висеть "мёртвого" неподтверждённого
            // пользователя, который потом молча блокирует этот логин/email навсегда.
            jdbc.update("DELETE FROM users WHERE login = ?", login);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body("Не удалось отправить письмо с кодом. Проверьте настройки почты на сервере и попробуйте снова.");
        }

        return ResponseEntity.ok("needsVerification");
    }

    @PostMapping("/register/verify")
    public ResponseEntity<?> verify(@RequestBody Map<String, Object> body, HttpServletResponse response) {
        String login = String.valueOf(body.get("login"));
        String code = String.valueOf(body.get("code"));
        boolean rememberMe = Boolean.TRUE.equals(body.get("rememberMe"))
            || "true".equalsIgnoreCase(String.valueOf(body.get("rememberMe")));

        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT verification_code, verification_expires, verified FROM users WHERE login = ?", login
        );
        if (rows.isEmpty()) {
            return ResponseEntity.status(404).body("Такая регистрация не найдена — начните заново");
        }
        Map<String, Object> row = rows.get(0);
        if (Boolean.TRUE.equals(row.get("verified"))) {
            return ResponseEntity.ok(Map.of("status", "ok", "login", login));
        }
        Timestamp expires = (Timestamp) row.get("verification_expires");
        if (expires == null || expires.toLocalDateTime().isBefore(LocalDateTime.now())) {
            return ResponseEntity.status(410).body("Код истёк — запросите новый");
        }
        String storedCode = (String) row.get("verification_code");
        if (storedCode == null || !storedCode.equals(code)) {
            return ResponseEntity.status(401).body("Неверный код");
        }

        jdbc.update(
            "UPDATE users SET verified = TRUE, verification_code = NULL, verification_expires = NULL WHERE login = ?",
            login
        );

        // Успешное подтверждение = успешный вход, как и после обычного логина.
        String token = jwtUtil.generateToken(login, rememberMe);
        CookieUtil.setAuthCookie(response, token, rememberMe);

        return ResponseEntity.ok(Map.of("status", "ok", "login", login));
    }

    @PostMapping("/register/resend")
    public ResponseEntity<String> resend(@RequestBody Map<String, String> body) {
        String login = body.get("login");
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT email, verified FROM users WHERE login = ?", login
        );
        if (rows.isEmpty()) {
            return ResponseEntity.status(404).body("Такая регистрация не найдена");
        }
        Map<String, Object> row = rows.get(0);
        if (Boolean.TRUE.equals(row.get("verified"))) {
            return ResponseEntity.badRequest().body("Этот аккаунт уже подтверждён, можно войти");
        }
        String email = (String) row.get("email");

        String code = generateCode();
        Timestamp expires = Timestamp.valueOf(LocalDateTime.now().plusMinutes(CODE_TTL_MINUTES));
        jdbc.update(
            "UPDATE users SET verification_code = ?, verification_expires = ? WHERE login = ?",
            code, expires, login
        );

        try {
            mailService.sendVerificationCode(email, code);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("Не удалось отправить письмо, попробуйте позже");
        }

        return ResponseEntity.ok("Код отправлен повторно");
    }

    private Boolean isVerified(String login) {
        List<Boolean> res = jdbc.queryForList(
            "SELECT verified FROM users WHERE login = ?", Boolean.class, login
        );
        return res.isEmpty() ? null : res.get(0);
    }

    private boolean isExpired(String login) {
        List<Timestamp> res = jdbc.queryForList(
            "SELECT verification_expires FROM users WHERE login = ?", Timestamp.class, login
        );
        if (res.isEmpty() || res.get(0) == null) return true;
        return res.get(0).toLocalDateTime().isBefore(LocalDateTime.now());
    }
}