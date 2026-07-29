package org.example;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Достаёт login текущего пользователя из httpOnly cookie "token" — тот же
 * источник правды, что использует AuthController#me. Любой эндпоинт, которому
 * нужно знать "кто сейчас залогинен" (например, привязка Google/Telegram к
 * своему аккаунту), должен брать login отсюда, а не из тела запроса —
 * иначе можно было бы привязать Google-аккаунт к ЧУЖОМУ логину, просто
 * подставив его в JSON.
 */
public class CurrentUser {

    /**
     * @return login текущего пользователя
     * @throws UnauthorizedException если cookie отсутствует или токен невалиден/просрочен
     */
    public static String require(HttpServletRequest request, JwtUtil jwtUtil) {
        String token = CookieUtil.readToken(request);
        if (token == null) {
            throw new UnauthorizedException("Не авторизован");
        }
        String login = jwtUtil.extractLogin(token);
        if (login == null) {
            throw new UnauthorizedException("Сессия истекла");
        }
        return login;
    }

    public static class UnauthorizedException extends RuntimeException {
        public UnauthorizedException(String message) {
            super(message);
        }
    }
}
