package org.example;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Привязка дополнительных способов входа (Google, Telegram) к УЖЕ
 * СУЩЕСТВУЮЩЕМУ, залогиненному аккаунту. В отличие от OAuthController /
 * TelegramAuthController (которые логинят/регистрируют нового пользователя
 * "с нуля"), эндпоинты здесь ничего не логинят — они дописывают google_id
 * / telegram_id текущему пользователю, которого достаём из httpOnly cookie
 * ("token"), а не из тела запроса. Это принципиально: если брать login из
 * тела запроса, любой мог бы прислать чужой login и угнать чужой аккаунт,
 * привязав к нему свой Google/Telegram.
 */
@RestController
@RequestMapping("/api/account")
public class AccountLinkController {

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;
    private final GoogleAuthService googleAuthService;
    private final TelegramAuthService telegramAuthService;
    // Не берём как бин через DI — в проекте нет отдельного @Bean
    // PasswordEncoder (регистрация хеширует пароли сама, без Spring
    // Security DI), поэтому создаём инстанс напрямую здесь же.
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AccountLinkController(JdbcTemplate jdbc, JwtUtil jwtUtil,
                                  GoogleAuthService googleAuthService,
                                  TelegramAuthService telegramAuthService) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
        this.googleAuthService = googleAuthService;
        this.telegramAuthService = telegramAuthService;
    }

    /**
     * Что сейчас привязано к аккаунту — фронту нужно это, чтобы решить,
     * показывать ли кнопку "Привязать Google" или "Google уже привязан".
     */
    @GetMapping("/providers")
    public ResponseEntity<?> providers(HttpServletRequest request) {
        String login;
        try {
            login = CurrentUser.require(request, jwtUtil);
        } catch (CurrentUser.UnauthorizedException e) {
            return ResponseEntity.status(401).body(e.getMessage());
        }

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT " +
            "(password IS NOT NULL) AS has_password, " +
            "(google_id IS NOT NULL) AS has_google, " +
            "(telegram_id IS NOT NULL) AS has_telegram, " +
            "email, telegram_username, avatar_url, login, name " +
            "FROM users WHERE login = ?",
            login
        );
        return ResponseEntity.ok(row);
    }

    /**
     * "Как к вам обращаться" — свободное отображаемое имя, отдельное от
     * login (которым логинятся) и не связанное ни с одним провайдером.
     */
    @PostMapping("/name")
    public ResponseEntity<?> updateName(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String login;
        try {
            login = CurrentUser.require(request, jwtUtil);
        } catch (CurrentUser.UnauthorizedException e) {
            return ResponseEntity.status(401).body(e.getMessage());
        }

        String name = body.get("name");
        if (name != null) name = name.trim();
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body("Имя не может быть пустым");
        }
        if (name.length() > 100) {
            return ResponseEntity.badRequest().body("Слишком длинное имя (максимум 100 символов)");
        }

        jdbc.update("UPDATE users SET name = ? WHERE login = ?", name, login);
        return ResponseEntity.ok(Map.of("status", "ok", "name", name));
    }

    /**
     * Смена пароля — либо первое добавление (у пользователя, вошедшего
     * только через Google/Telegram, password IS NULL), либо обычная смена.
     * В первом случае currentPassword не проверяем — сверять нечего.
     */
    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String login;
        try {
            login = CurrentUser.require(request, jwtUtil);
        } catch (CurrentUser.UnauthorizedException e) {
            return ResponseEntity.status(401).body(e.getMessage());
        }

        String currentPassword = body.get("currentPassword");
        String newPassword = body.get("newPassword");

        if (newPassword == null
                || newPassword.length() < 6
                || !newPassword.matches("^[A-Z].*")
                || !newPassword.matches(".*[0-9!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?~`].*")
                || !newPassword.matches("^[A-Za-z0-9!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?~`]*$")) {
            return ResponseEntity.badRequest().body("Новый пароль не соответствует требованиям");
        }

        String storedHash = jdbc.queryForObject(
            "SELECT password FROM users WHERE login = ?", String.class, login);

        if (storedHash != null) {
            // Пароль уже есть — это смена, а не первое добавление. Обязательно
            // сверяем текущий, иначе кто угодно с угнанной сессией мог бы
            // переустановить пароль без знания старого.
            if (currentPassword == null || !passwordEncoder.matches(currentPassword, storedHash)) {
                return ResponseEntity.status(401).body("Неверный текущий пароль");
            }
        }

        jdbc.update("UPDATE users SET password = ? WHERE login = ?",
            passwordEncoder.encode(newPassword), login);

        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @PostMapping("/link/google")
    public ResponseEntity<?> linkGoogle(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String login;
        try {
            login = CurrentUser.require(request, jwtUtil);
        } catch (CurrentUser.UnauthorizedException e) {
            return ResponseEntity.status(401).body(e.getMessage());
        }

        String accessToken = body.get("accessToken");
        if (accessToken == null || accessToken.isBlank()) {
            return ResponseEntity.badRequest().body("Отсутствует accessToken");
        }

        JsonNode profile;
        try {
            profile = googleAuthService.verifyGoogleToken(accessToken);
        } catch (Exception e) {
            return ResponseEntity.status(401).body("Не удалось проверить токен Google: " + e.getMessage());
        }

        String googleId = profile.has("sub") ? profile.get("sub").asText() : null;
        String email = profile.has("email") ? profile.get("email").asText() : null;
        if (googleId == null) {
            return ResponseEntity.status(401).body("Google не вернул идентификатор пользователя");
        }

        List<String> byGoogleId = jdbc.queryForList(
            "SELECT login FROM users WHERE google_id = ?", String.class, googleId);
        if (!byGoogleId.isEmpty() && !byGoogleId.get(0).equals(login)) {
            return ResponseEntity.status(409).body("Этот Google-аккаунт уже привязан к другому пользователю");
        }

        // email заполняем, только если у аккаунта его ещё нет — не затираем
        // существующий email пользователя чужим/другим адресом от Google.
        jdbc.update(
            "UPDATE users SET google_id = ?, email = COALESCE(email, ?) WHERE login = ?",
            googleId, email, login
        );

        return ResponseEntity.ok(Map.of("status", "ok", "provider", "google"));
    }

    @PostMapping("/link/telegram")
    public ResponseEntity<?> linkTelegram(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String login;
        try {
            login = CurrentUser.require(request, jwtUtil);
        } catch (CurrentUser.UnauthorizedException e) {
            return ResponseEntity.status(401).body(e.getMessage());
        }

        try {
            telegramAuthService.verify(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body(e.getMessage());
        }

        String telegramId = body.get("id");
        String telegramUsername = body.get("username"); // может быть null — у юзера Telegram нет обязательного username

        List<String> byTelegramId = jdbc.queryForList(
            "SELECT login FROM users WHERE telegram_id = ?", String.class, telegramId);
        if (!byTelegramId.isEmpty() && !byTelegramId.get(0).equals(login)) {
            return ResponseEntity.status(409).body("Этот Telegram-аккаунт уже привязан к другому пользователю");
        }

        jdbc.update("UPDATE users SET telegram_id = ?, telegram_username = ? WHERE login = ?",
            telegramId, telegramUsername, login);

        return ResponseEntity.ok(Map.of("status", "ok", "provider", "telegram"));
    }

    @PostMapping("/unlink/google")
    public ResponseEntity<?> unlinkGoogle(HttpServletRequest request) {
        return unlink(request, "google_id");
    }

    @PostMapping("/unlink/telegram")
    public ResponseEntity<?> unlinkTelegram(HttpServletRequest request) {
        return unlink(request, "telegram_id");
    }

    // Отвязка запрещена, если после неё у пользователя не останется ни
    // одного способа войти (ни пароля, ни другого провайдера) — иначе
    // человек случайно потеряет доступ к своему аккаунту навсегда.
    private ResponseEntity<?> unlink(HttpServletRequest request, String column) {
        String login;
        try {
            login = CurrentUser.require(request, jwtUtil);
        } catch (CurrentUser.UnauthorizedException e) {
            return ResponseEntity.status(401).body(e.getMessage());
        }

        Map<String, Object> row = jdbc.queryForMap(
            "SELECT " +
            "(password IS NOT NULL) AS has_password, " +
            "(google_id IS NOT NULL) AS has_google, " +
            "(telegram_id IS NOT NULL) AS has_telegram " +
            "FROM users WHERE login = ?",
            login
        );
        boolean hasPassword = (boolean) row.get("has_password");
        boolean hasGoogle = (boolean) row.get("has_google");
        boolean hasTelegram = (boolean) row.get("has_telegram");

        int remainingMethods = (hasPassword ? 1 : 0) + (hasGoogle ? 1 : 0) + (hasTelegram ? 1 : 0);
        boolean unlinkingTheOnlyOne =
            ("google_id".equals(column) && hasGoogle && !hasPassword && !hasTelegram) ||
            ("telegram_id".equals(column) && hasTelegram && !hasPassword && !hasGoogle);

        if (remainingMethods <= 1 || unlinkingTheOnlyOne) {
            return ResponseEntity.status(409).body(
                "Нельзя отвязать единственный способ входа — сначала задайте пароль или привяжите другой способ");
        }

        if ("telegram_id".equals(column)) {
            jdbc.update("UPDATE users SET telegram_id = NULL, telegram_username = NULL WHERE login = ?", login);
        } else {
            jdbc.update("UPDATE users SET " + column + " = NULL WHERE login = ?", login);
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}