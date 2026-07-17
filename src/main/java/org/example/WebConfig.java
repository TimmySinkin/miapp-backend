package org.example;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * ВАЖНО: для httpOnly cookie нужен allowCredentials(true) — без него браузер
 * не отправит cookie на кросс-origin запрос (фронт на 5173, бэк на 8080).
 * allowedOrigins нельзя оставлять "*" вместе с allowCredentials(true) —
 * браузер это запретит, поэтому перечисляем конкретные origin явно.
 *
 * Если у вас уже есть свой WebMvcConfigurer/CorsFilter — не подключайте оба
 * сразу, оставьте один и перенесите allowCredentials(true) туда.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(
                    "http://localhost:5173", // локальная разработка
                    "https://miniapp-frontend-xi.vercel.app" // прод-домен на Vercel
                )
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}