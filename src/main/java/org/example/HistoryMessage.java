package org.example;

import java.util.List;

/**
 * Одно сообщение из истории переписки чата, присылаемое с фронта.
 * role = "user" | "bot"
 */
public class HistoryMessage {

    private String role;
    private String text;

    // Вложения этого сообщения (только текстовые файлы пересылаются в истории —
    // картинки в history не шлём, чтобы не раздувать payload base64-данными
    // на каждый последующий ход; vision и так работает в рамках одного хода).
    // Нужно, чтобы модель не "теряла" содержимое файла на следующем сообщении
    // после того, как он был прикреплён — раньше в history попадал только
    // текст реплики ("прочти документ"), а сам файл терялся.
    private List<Attachment> attachments;

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public List<Attachment> getAttachments() {
        return attachments;
    }

    public void setAttachments(List<Attachment> attachments) {
        this.attachments = attachments;
    }
}