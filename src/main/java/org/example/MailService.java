package org.example;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;

@Service
public class MailService {

    private final JavaMailSender mailSender;

    // Адрес — тот же, что в spring.mail.username (реальный Gmail-ящик,
    // менять его отдельно не нужно). Отображаемое имя — то, что получатель
    // увидит вместо адреса в клиенте почты, например "MiniApp" вместо
    // "your-sender-account@gmail.com". Вынесено в application.properties,
    // чтобы не перекомпилировать код при смене названия.
    @Value("${spring.mail.username}")
    private String fromEmail;

    @Value("${app.mail.sender-name:MiniApp}")
    private String senderName;

    public MailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    private void send(String toEmail, String subject, String text) {
        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, "UTF-8");
            // InternetAddress(email, personal, charset) — именно так корректно
            // кодируется отображаемое имя, если оно на кириллице (RFC 2047),
            // иначе почтовые клиенты могут показать имя криво или проигнорировать его.
            helper.setFrom(new InternetAddress(fromEmail, senderName, "UTF-8"));
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(text);
            mailSender.send(mimeMessage);
        } catch (jakarta.mail.MessagingException | UnsupportedEncodingException e) {
            throw new RuntimeException("Не удалось отправить письмо", e);
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