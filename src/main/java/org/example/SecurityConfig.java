package org.example;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

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
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}