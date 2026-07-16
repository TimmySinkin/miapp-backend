package org.example;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Источник правды о том, кто сейчас залогинен — читает httpOnly cookie
 * "token", а не доверяет тому, что фронт прислал в URL/теле запроса.
 * Home/AI/Stats на фронте должны получать login именно отсюда, а не из
 * localStorage — иначе "запомнить меня" не будет иметь смысла: localStorage
 * живёт вечно независимо от галочки.
 */
@RestController
@RequestMapping("/api")
public class AuthController {

    private final JwtUtil jwtUtil;

    public AuthController(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest request) {
        String token = CookieUtil.readToken(request);
        if (token == null) {
            return ResponseEntity.status(401).body("Не авторизован");
        }
        String login = jwtUtil.extractLogin(token);
        if (login == null) {
            return ResponseEntity.status(401).body("Сессия истекла");
        }
        return ResponseEntity.ok(Map.of("login", login));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletResponse response) {
        CookieUtil.clearAuthCookie(response);
        return ResponseEntity.ok().build();
    }
}