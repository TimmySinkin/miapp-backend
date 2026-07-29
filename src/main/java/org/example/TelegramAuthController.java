package org.example;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
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
 */
@RestController
@RequestMapping("/api/oauth")
public class TelegramAuthController {

    @Value("${telegram.bot.token}")
    private String botToken;

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;

    private static final long MAX_AUTH_AGE_SECONDS = 24 * 60 * 60;

    // Telegram подписывает ТОЛЬКО эти поля. Любое постороннее поле в теле
    // запроса (например rememberMe, добавленное фронтом в тот же объект)
    // не должно попадать в check-string, иначе hash никогда не совпадёт.
    private static final java.util.Set<String> TELEGRAM_FIELDS = java.util.Set.of(
        "id", "first_name", "last_name", "username", "photo_url", "auth_date"
    );

    public TelegramAuthController(JdbcTemplate jdbc, JwtUtil jwtUtil) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
    }

    private void verifyTelegramAuth(Map<String, String> data) {
        String hash = data.get("hash");
        if (hash == null || hash.isBlank()) {
            throw new IllegalArgumentException("Отсутствует подпись Telegram");
        }

        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : data.entrySet()) {
            if (TELEGRAM_FIELDS.contains(entry.getKey())) {
                sorted.put(entry.getKey(), entry.getValue());
            }
        }
        StringBuilder checkString = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (checkString.length() > 0) checkString.append('\n');
            checkString.append(entry.getKey()).append('=').append(entry.getValue());
        }

        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] secretKey = sha256.digest(botToken.getBytes(StandardCharsets.UTF_8));

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey, "HmacSHA256"));
            byte[] computed = mac.doFinal(checkString.toString().getBytes(StandardCharsets.UTF_8));

            StringBuilder hex = new StringBuilder();
            for (byte b : computed) hex.append(String.format("%02x", b));

            if (!hex.toString().equals(hash)) {
                throw new IllegalArgumentException("Неверная подпись Telegram — данные могли быть подделаны");
            }
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new RuntimeException("Ошибка проверки подписи Telegram", e);
        }

        String authDateStr = data.get("auth_date");
        if (authDateStr == null) {
            throw new IllegalArgumentException("Отсутствует auth_date");
        }
        long authDate = Long.parseLong(authDateStr);
        long now = Instant.now().getEpochSecond();
        if (now - authDate > MAX_AUTH_AGE_SECONDS) {
            throw new IllegalArgumentException("Данные авторизации устарели, попробуйте войти заново");
        }
    }

    private String suggestLogin(Map<String, String> data) {
        String base = data.get("username");
        if (base == null || base.isBlank()) {
            base = data.get("first_name");
        }
        if (base == null) return "";
        return base.toLowerCase().replaceAll("[^a-zA-Zа-яА-Я0-9_]", "");
    }

    @PostMapping("/telegram")
    public ResponseEntity<?> telegramLogin(@RequestBody Map<String, String> body, HttpServletResponse response) {
        try {
            verifyTelegramAuth(body);
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
            "suggestedLogin", suggestLogin(body)
        ));
    }

    @PostMapping("/telegram/complete")
    public ResponseEntity<?> telegramComplete(@RequestBody Map<String, String> body, HttpServletResponse response) {
        try {
            verifyTelegramAuth(body);
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