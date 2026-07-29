package org.example;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Вход через Telegram Login Widget. Виджет на фронте сам получает подпись от
 * Telegram и присылает нам весь набор полей (id, first_name, ..., hash) —
 * никакого отдельного обмена токенами не нужно, достаточно проверить hash
 * тем же алгоритмом, что описан в доке Telegram Login Widget:
 * https://core.telegram.org/widgets/login#checking-authorization
 *
 * Сама проверка подписи вынесена в TelegramAuthService — им же пользуется
 * AccountLinkController при привязке Telegram к уже существующему аккаунту.
 */
@RestController
@RequestMapping("/api/oauth")
public class TelegramAuthController {

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;
    private final TelegramAuthService telegramAuthService;

    public TelegramAuthController(JdbcTemplate jdbc, JwtUtil jwtUtil, TelegramAuthService telegramAuthService) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
        this.telegramAuthService = telegramAuthService;
    }

    @PostMapping("/telegram")
    public ResponseEntity<?> telegramLogin(@RequestBody Map<String, String> body, HttpServletResponse response) {
        try {
            telegramAuthService.verify(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body(e.getMessage());
        }

        boolean rememberMe = "true".equalsIgnoreCase(body.get("rememberMe"));
        String telegramId = body.get("id");

        List<String> byTelegramId = jdbc.queryForList(
            "SELECT login FROM users WHERE telegram_id = ?", String.class, telegramId
        );
        if (!byTelegramId.isEmpty()) {
            String existingLogin = byTelegramId.get(0);
            String token = jwtUtil.generateToken(existingLogin, rememberMe);
            CookieUtil.setAuthCookie(response, token, rememberMe);
            return ResponseEntity.ok(Map.of("status", "ok", "login", existingLogin));
        }

        return ResponseEntity.ok(Map.of(
            "status", "needsOnboarding",
            "suggestedLogin", telegramAuthService.suggestLogin(body)
        ));
    }

    @PostMapping("/telegram/complete")
    public ResponseEntity<?> telegramComplete(@RequestBody Map<String, String> body, HttpServletResponse response) {
        try {
            telegramAuthService.verify(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body(e.getMessage());
        }

        boolean rememberMe = "true".equalsIgnoreCase(body.get("rememberMe"));
        String telegramId = body.get("id");
        String chosenLogin = body.get("login");

        if (chosenLogin == null || chosenLogin.isBlank()) {
            return ResponseEntity.badRequest().body("Укажите, как к вам обращаться");
        }
        chosenLogin = chosenLogin.trim();
        if (!chosenLogin.matches("^[a-zA-Zа-яА-Я0-9_.]{2,24}$")) {
            return ResponseEntity.badRequest().body(
                "Логин может содержать только буквы, цифры, точку и подчёркивание, от 2 до 24 символов");
        }

        List<String> byTelegramId = jdbc.queryForList(
            "SELECT login FROM users WHERE telegram_id = ?", String.class, telegramId
        );
        if (!byTelegramId.isEmpty()) {
            String existingLogin = byTelegramId.get(0);
            String token = jwtUtil.generateToken(existingLogin, rememberMe);
            CookieUtil.setAuthCookie(response, token, rememberMe);
            return ResponseEntity.ok(Map.of("status", "ok", "login", existingLogin));
        }

        List<String> clash = jdbc.queryForList(
            "SELECT login FROM users WHERE login = ?", String.class, chosenLogin
        );
        if (!clash.isEmpty()) {
            return ResponseEntity.status(409).body("Такой логин уже занят, выберите другой");
        }

        jdbc.update(
            "INSERT INTO users (login, password, name, email, telegram_id) VALUES (?, NULL, ?, NULL, ?)",
            chosenLogin, body.get("first_name"), telegramId
        );

        String token = jwtUtil.generateToken(chosenLogin, rememberMe);
        CookieUtil.setAuthCookie(response, token, rememberMe);
        return ResponseEntity.ok(Map.of("status", "ok", "login", chosenLogin));
    }
}