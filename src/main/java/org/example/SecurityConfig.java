package org.example;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * spring-boot-starter-security уже подключен в pom.xml, но без своего
 * SecurityConfig он включает дефолтную защиту (basic auth / login form на
 * ВСЕ эндпоинты) — это, скорее всего, сломало бы приложение целиком, так что
 * либо у вас уже есть такой конфиг в другом месте (тогда этот файл не нужен,
 * не подключайте оба сразу — будет конфликт бинов SecurityFilterChain),
 * либо CSRF/дефолтная защита сейчас случайно отключены как-то ещё.
 *
 * Здесь пропускаем все /api/** через фильтр Spring Security без проверки —
 * авторизацию делаем сами через httpOnly-cookie + JwtUtil в AuthController/
 * контроллерах (см. /api/me). CSRF отключаем, т.к. используем JSON API +
 * SameSite=Lax cookie, а не формы с сессией на сервере.
 *
 * CORS: фронт (www.caltrack.ru) и API (api.caltrack.ru) — РАЗНЫЕ origin'ы
 * с точки зрения браузера (разные поддомены), а запросы идут с
 * credentials: "include" (httpOnly-cookie сессии). Без явного CORS-конфига
 * Spring Security по умолчанию не шлёт Access-Control-Allow-Origin вообще —
 * запрос уходит на сервер, но браузер блокирует чтение ответа
 * ("has been blocked by CORS policy: No 'Access-Control-Allow-Origin' header").
 * Credentialed-запросы НЕЛЬЗЯ обслуживать через "*" — Access-Control-Allow-Origin
 * должен быть конкретным origin'ом, поэтому ниже — явный список.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // Оба варианта домена (с www и без) — на случай, если пользователь
        // заходит на caltrack.ru напрямую, без редиректа на www.
        config.setAllowedOrigins(List.of(
            "https://www.caltrack.ru",
            "https://caltrack.ru"
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        // Обязательно true — иначе браузер не отправит/не примет httpOnly-cookie
        // сессии в кросс-доменных запросах (credentials: "include" на фронте).
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}