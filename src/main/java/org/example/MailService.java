package org.example;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SesV2Exception;

import java.net.URI;

/**
 * Письма отправляются через Yandex Cloud Postbox (AWS SES-совместимый API),
 * а не через прямой SMTP. Причины сразу две:
 *  1) Render на бесплатном тарифе с 26 сентября 2025 блокирует исходящий
 *     трафик на SMTP-порты 25/465/587 (обычный HTTPS/443 — не блокирует).
 *  2) Зарубежные HTTP-провайдеры транзакционной почты (Brevo, SendGrid и
 *     т.п.) не дают выбрать Россию при регистрации из-за санкционных
 *     ограничений в сфере услуг — Yandex Cloud для российского юрлица/
 *     физлица этой проблемы не создаёт.
 *
 * Postbox API-совместим с AWS SES, поэтому используется тот же AWS SDK,
 * что и для Yandex Object Storage (см. StorageService.java) — просто с
 * другим клиентом (SesV2Client) и другим базовым URL.
 */
@Service
public class MailService {

    // Тот же сервисный аккаунт/статический ключ, что и для Object Storage,
    // можно переиспользовать, если ему выдана роль postbox.sender —
    // либо завести отдельный сервисный аккаунт специально для почты.
    @Value("${yandex.postbox.access-key}")
    private String accessKey;

    @Value("${yandex.postbox.secret-key}")
    private String secretKey;

    // Адрес отправителя — должен быть в домене, прошедшем проверку
    // владения в Yandex Cloud Postbox (Postbox → Создать адрес →
    // Проверка владения доменом), например noreply@caltrack.ru.
    @Value("${yandex.postbox.sender}")
    private String fromEmail;

    private static final String POSTBOX_ENDPOINT = "https://postbox.cloud.yandex.net";
    private static final Region POSTBOX_REGION = Region.of("ru-central1");

    private SesV2Client client() {
        return SesV2Client.builder()
            .region(POSTBOX_REGION)
            .endpointOverride(URI.create(POSTBOX_ENDPOINT))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey)))
            .build();
    }

    private void send(String toEmail, String subject, String text) {
        try (SesV2Client sesClient = client()) {
            SendEmailRequest request = SendEmailRequest.builder()
                .fromEmailAddress(fromEmail)
                .destination(Destination.builder().toAddresses(toEmail).build())
                .content(EmailContent.builder()
                    .simple(Message.builder()
                        .subject(Content.builder().data(subject).charset("UTF-8").build())
                        .body(Body.builder()
                            .text(Content.builder().data(text).charset("UTF-8").build())
                            .build())
                        .build())
                    .build())
                .build();

            sesClient.sendEmail(request);
        } catch (SesV2Exception e) {
            throw new RuntimeException("Не удалось отправить письмо через Yandex Cloud Postbox: "
                + e.awsErrorDetails().errorMessage(), e);
        }
    }

    public void sendVerificationCode(String toEmail, String code) {
        send(
            toEmail,
            "Код подтверждения регистрации",
            "Ваш код подтверждения: " + code + "\n\n" +
            "Код действителен 10 минут. Если вы не регистрировались — просто проигнорируйте это письмо."
        );
    }

    public void sendPasswordResetCode(String toEmail, String code) {
        send(
            toEmail,
            "Код для сброса пароля",
            "Ваш код для сброса пароля: " + code + "\n\n" +
            "Код действителен 10 минут. Если вы не запрашивали сброс пароля — просто проигнорируйте это письмо, ваш пароль не изменится."
        );
    }
}