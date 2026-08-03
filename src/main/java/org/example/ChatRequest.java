package org.example;

import java.util.List;

public class ChatRequest {

    private String message;
    private String lang;
    private String goalText; // исходная цель чата (первое сообщение) — для привязки темы разговора
    private List<Attachment> attachments;
    private List<HistoryMessage> history;

    // Опциональный флаг "точного режима" — Yandex генерирует черновик,
    // Groq (через прокси) рецензирует его перед отправкой пользователю.
    // По умолчанию false: если фронт не передаёт это поле вообще, Jackson
    // оставит его false и поведение чата не изменится (обычный failover).
    private boolean ensembleMode;

    public String getGoalText() {
        return goalText;
    }

    public void setGoalText(String goalText) {
        this.goalText = goalText;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getLang() {
        return lang;
    }

    public void setLang(String lang) {
        this.lang = lang;
    }

    public List<Attachment> getAttachments() {
        return attachments;
    }

    public void setAttachments(List<Attachment> attachments) {
        this.attachments = attachments;
    }

    public List<HistoryMessage> getHistory() {
        return history;
    }

    public void setHistory(List<HistoryMessage> history) {
        this.history = history;
    }

    // true, если в этом чате УЖЕ был хотя бы один раунд уточняющих вопросов
    // (см. clarify-контракт в /chat) — фронт вычисляет это по истории сам
    // и передаёт явно, чтобы бэкенд мог ДЕТЕРМИНИРОВАННО запретить второй
    // раунд, не полагаясь на то, что модель сама "не забудет" это правило.
    private boolean clarifyUsed;

    public boolean isClarifyUsed() {
        return clarifyUsed;
    }

    public void setClarifyUsed(boolean clarifyUsed) {
        this.clarifyUsed = clarifyUsed;
    }

    public boolean isEnsembleMode() {
        return ensembleMode;
    }

    public void setEnsembleMode(boolean ensembleMode) {
        this.ensembleMode = ensembleMode;
    }
}