package org.example;

/**
 * Вложение в сообщении AI-чата. type: "text" (содержимое файла как текст)
 * или "image" (content — сама картинка, обычно base64/data URL — см.
 * использование в ClaudeController.chat: text-вложения дописываются в
 * текст сообщения, image-вложения уходят отдельным списком images в Ollama).
 */
public class Attachment {

    private String type;
    private String name;
    private String content;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
