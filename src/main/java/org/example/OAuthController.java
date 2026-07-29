package org.example;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Вход через Google. Сама проверка access_token у Google вынесена в
 * GoogleAuthService — им же пользуется AccountLinkController при привязке
 * Google к уже существующему аккаунту.
 */
@RestController
@RequestMapping("/api/oauth")
public class OAuthController {

    private final JdbcTemplate jdbc;
    private final JwtUtil jwtUtil;
    private final GoogleAuthService googleAuthService;

    public OAuthController(JdbcTemplate jdbc, JwtUtil jwtUtil, GoogleAuthService googleAuthService) {
        this.jdbc = jdbc;
        this.jwtUtil = jwtUtil;
        this.googleAuthService = googleAuthService;
    }

    // "accessToken" от Google — это OAuth-токен для похода в userinfo, а не
    // наш JWT сессии. rememberMe читаем как обычное текстовое поле "true"/"false",
    // т.к. body здесь Map<String,String>.
    private static boolean parseRememberMe(Map<String, String> body) {
        return "true".equalsIgnoreCase(body.get("rememberMe"));
    }

    // Из имени/email пытаемся предложить приятный логин-заготовку —
    // пользователь всё равно сам его подтвердит/поправит на шаге онбординга,
    // это только подсказка в поле ввода, а не финальное решение.
    private String suggestLogin(String name, String email) {
        String base = null;
        if (name != null && !name.isBlank()) {
            base = name.toLowerCase().replaceAll("[^a-zA-Zа-яА-Я0-9]", "");
        }
        if ((base == null || base.isBlank()) && email != null && email.contains("@")) {
            base = email.substring(0, email.indexOf('@')).replaceAll("[^a-zA-Z0-9_.]", "");
        }
        return (base == null || base.isBlank()) ? "" : base;
    }

    // Шаг 1: фронт присылает access_token, полученный через Google Identity
    // Services. Мы проверяем его у Google и смотрим — уже есть у нас такой
    // пользователь (по google_id или по email) или это первый вход.
    @PostMapping("/google")
    public ResponseEntity<?> googleLogin(@RequestBody Map<String, String> body, HttpServletResponse response) {
        boolean rememberMe = parseRememberMe(body);
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
        String name = profile.has("name") ? profile.get("name").asText() : null;

        if (googleId == null) {
            return ResponseEntity.status(401).body("Google не вернул идентификатор пользователя");
        }

        // 1) Уже входили через этот Google-аккаунт раньше — просто логиним.
        List<String> byGoogleId = jdbc.queryForList(
            "SELECT login FROM users WHERE google_id = ?", String.class, googleId);
        if (!byGoogleId.isEmpty()) {
            String existingLogin = byGoogleId.get(0);
            String token = jwtUtil.generateToken(existingLogin, rememberMe);
            CookieUtil.setAuthCookie(response, token, rememberMe);
            return ResponseEntity.ok(Map.of("status", "ok", "login", existingLogin));
        }

        // 2) Аккаунт с таким email уже существует (заведён обычной регистрацией) —
        // привязываем Google к нему, чтобы дальше входить и так, и так.
        if (email != null) {
            List<String> byEmail = jdbc.queryForList(
                "SELECT login FROM users WHERE email = ?", String.class, email);
            if (!byEmail.isEmpty()) {
                String existingLogin = byEmail.get(0);
                jdbc.update("UPDATE users SET google_id = ? WHERE login = ?", googleId, existingLogin);
                String token = jwtUtil.generateToken(existingLogin, rememberMe);
                CookieUtil.setAuthCookie(response, token, rememberMe);
                return ResponseEntity.ok(Map.of("status", "ok", "login", existingLogin));
            }
        }

        // 3) Новый пользователь — НЕ создаём его сразу молча под автосгенерированным
        // логином (это и была проблема: "google_1071206192" непонятно пользователю).
        // Вместо этого просим фронт показать шаг "как к вам обращаться" и
        // прислать выбранный логин отдельным запросом на /google/complete.
        return ResponseEntity.ok(Map.of(
            "status", "needsOnboarding",
            "suggestedLogin", suggestLogin(name, email)
        ));
    }

    // Шаг 2: пользователь подтвердил/поправил логин на фронте. Токен
    // проверяем у Google ЕЩЁ РАЗ (а не доверяем googleId/email, присланным
    // с фронта на этом шаге) — так нельзя подделать чужой аккаунт, отправив
    // произвольный googleId напрямую на этот эндпоинт.
    @PostMapping("/google/complete")
    public ResponseEntity<?> googleComplete(@RequestBody Map<String, String> body, HttpServletResponse response) {
        boolean rememberMe = parseRememberMe(body);
        String accessToken = body.get("accessToken");
        String chosenLogin = body.get("login");

        if (accessToken == null || accessToken.isBlank()) {
            return ResponseEntity.badRequest().body("Отсутствует accessToken");
        }
        if (chosenLogin == null || chosenLogin.isBlank()) {
            return ResponseEntity.badRequest().body("Укажите, как к вам обращаться");
        }
        chosenLogin = chosenLogin.trim();
        if (!chosenLogin.matches("^[a-zA-Zа-яА-Я0-9_.]{2,24}$")) {
            return ResponseEntity.badRequest().body(
                "Логин может содержать только буквы, цифры, точку и подчёркивание, от 2 до 24 символов");
        }

        JsonNode profile;
        try {
            profile = googleAuthService.verifyGoogleToken(accessToken);
        } catch (Exception e) {
            return ResponseEntity.status(401).body("Не удалось проверить токен Google: " + e.getMessage());
        }

        String googleId = profile.has("sub") ? profile.get("sub").asText() : null;
        String email = profile.has("email") ? profile.get("email").asText() : null;
        String name = profile.has("name") ? profile.get("name").asText() : null;

        if (googleId == null) {
            return ResponseEntity.status(401).body("Google не вернул идентификатор пользователя");
        }

        // На случай гонки/повторного клика — если за это время уже создан
        // аккаунт с этим google_id, просто возвращаем его, не плодим дубликаты.
        List<String> byGoogleId = jdbc.queryForList(
            "SELECT login FROM users WHERE google_id = ?", String.class, googleId);
        if (!byGoogleId.isEmpty()) {
            String existingLogin = byGoogleId.get(0);
            String token = jwtUtil.generateToken(existingLogin, rememberMe);
            CookieUtil.setAuthCookie(response, token, rememberMe);
            return ResponseEntity.ok(Map.of("status", "ok", "login", existingLogin));
        }

        List<String> clash = jdbc.queryForList(
            "SELECT login FROM users WHERE login = ?", String.class, chosenLogin);
        if (!clash.isEmpty()) {
            return ResponseEntity.status(409).body("Такой логин уже занят, выберите другой");
        }

        jdbc.update(
            "INSERT INTO users (login, password, name, email, google_id) VALUES (?, NULL, ?, ?, ?)",
            chosenLogin, name, email, googleId
        );

        String token = jwtUtil.generateToken(chosenLogin, rememberMe);
        CookieUtil.setAuthCookie(response, token, rememberMe);
        return ResponseEntity.ok(Map.of("status", "ok", "login", chosenLogin));
    }
}
