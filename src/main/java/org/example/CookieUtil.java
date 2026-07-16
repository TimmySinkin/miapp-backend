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
     * sameSite=Lax и без Secure — подходит для локальной разработки по http://localhost.
     * В проде (https) обязательно добавьте .secure(true) и рассмотрите sameSite=Strict/None
     * в зависимости от того, с каким доменом фронтенд.
     */
    public static void setAuthCookie(HttpServletResponse response, String token, boolean rememberMe) {
        Cookie cookie = new Cookie(COOKIE_NAME, token);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        // Secure нужно включить в проде под https, иначе браузер не примет
        // sameSite=None; для localhost/http Lax работает без Secure.
        cookie.setSecure(false);
        cookie.setAttribute("SameSite", "Lax");
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
