package org.example;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;

public class CookieUtil {

    public static final String COOKIE_NAME = "token";

    /**
     * Ставит httpOnly cookie с JWT.
     *
     * rememberMe = true  -> maxAge задан (30 дней) -> persistent cookie, переживает закрытие браузера.
     * rememberMe = false -> maxAge НЕ задаём (-1)   -> session cookie, удаляется браузером при закрытии.
     *
     * SameSite=None + Secure — нужно, если фронтенд и бэкенд на разных сайтах
     * с точки зрения браузера (например, фронт через туннель loca.lt/ngrok,
     * бэкенд на localhost:8080 — для браузера это разные сайты, и SameSite=Lax
     * такую куку кросс-доменно просто не отправит). Secure требует https у
     * ОТВЕТА, который ставит куку — сам localhost браузеры считают
     * "potentially trustworthy", поэтому Set-Cookie с Secure с обычного
     * http://localhost:8080 всё равно принимается в Chrome/Firefox.
     * Если разворачиваете на реальном проде — это в любом случае то, что
     * нужно (Secure тогда обязателен по-настоящему, т.к. трафик реальный).
     */
    public static void setAuthCookie(HttpServletResponse response, String token, boolean rememberMe) {
        Cookie cookie = new Cookie(COOKIE_NAME, token);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setSecure(true);
        cookie.setAttribute("SameSite", "None");
        if (rememberMe) {
            cookie.setMaxAge((int) JwtUtil.REMEMBER_TTL.getSeconds());
        } else {
            cookie.setMaxAge(-1); // session cookie — живёт до закрытия браузера
        }
        response.addCookie(cookie);
    }

    public static void clearAuthCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie(COOKIE_NAME, "");
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }

    public static String readToken(jakarta.servlet.http.HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie c : request.getCookies()) {
            if (COOKIE_NAME.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
