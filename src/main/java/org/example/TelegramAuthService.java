package org.example;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Проверка подписи данных от Telegram Login Widget. Вынесено из
 * TelegramAuthController, чтобы этой же проверкой мог пользоваться
 * AccountLinkController при привязке Telegram к уже существующему аккаунту.
 * Алгоритм — см. https://core.telegram.org/widgets/login#checking-authorization
 */
@Service
public class TelegramAuthService {

    @Value("${telegram.bot.token}")
    private String botToken;

    private static final long MAX_AUTH_AGE_SECONDS = 24 * 60 * 60;

    // Telegram подписывает ТОЛЬКО эти поля. Любое постороннее поле в теле
    // запроса (например rememberMe, добавленное фронтом в тот же объект)
    // не должно попадать в check-string, иначе hash никогда не совпадёт.
    private static final Set<String> TELEGRAM_FIELDS = Set.of(
        "id", "first_name", "last_name", "username", "photo_url", "auth_date"
    );

    public void verify(Map<String, String> data) {
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
        } catch (java.security.NoSuchAlgorithmException | java.security.InvalidKeyException e) {
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

    public String suggestLogin(Map<String, String> data) {
        String base = data.get("username");
        if (base == null || base.isBlank()) {
            base = data.get("first_name");
        }
        if (base == null) return "";
        return base.toLowerCase().replaceAll("[^a-zA-Zа-яА-Я0-9_]", "");
    }
}
