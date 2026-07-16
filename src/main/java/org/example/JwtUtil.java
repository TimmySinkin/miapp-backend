package org.example;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.util.Date;

/**
 * Генерация и проверка JWT для сессии пользователя.
 *
 * Длительность жизни токена зависит от "запомнить меня":
 *  - rememberMe = true  -> токен живёт REMEMBER_TTL (напр. 30 дней),
 *                          и cookie тоже ставится с таким же maxAge (persistent cookie).
 *  - rememberMe = false -> токен живёт SESSION_TTL (напр. 12 часов) на случай, если
 *                          вкладка провисит долго, а cookie ставится БЕЗ maxAge —
 *                          это делает её session-cookie: браузер сам удалит её при
 *                          закрытии браузера, что и даёт эффект "не запоминать".
 *
 * ВАЖНО: SECRET здесь захардкожен для примера. В реальном проекте вынесите его
 * в переменную окружения / application.properties (jwt.secret) и никогда не
 * коммитьте в репозиторий.
 */
@Component
public class JwtUtil {

    // Замените на случайную строку не короче 32 байт и вынесите в конфиг/ENV.
    private static final String SECRET = "CHANGE_ME_super_secret_key_at_least_32_bytes_long!!";
    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes());

    public static final Duration REMEMBER_TTL = Duration.ofDays(30);
    public static final Duration SESSION_TTL = Duration.ofHours(12);

    public String generateToken(String login, boolean rememberMe) {
        Duration ttl = rememberMe ? REMEMBER_TTL : SESSION_TTL;
        Date now = new Date();
        Date expiry = new Date(now.getTime() + ttl.toMillis());

        return Jwts.builder()
                .subject(login)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(KEY)
                .compact();
    }

    /**
     * Возвращает login из токена, либо null если токен невалиден/просрочен.
     */
    public String extractLogin(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(KEY)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return claims.getSubject();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}