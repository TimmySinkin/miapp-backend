package org.example;

public class DayTask {
    private Long id;
    private String login;
    private String date;
    private String text;
    private Double goalCount;
    private Double progress;
    private Integer position;
    private String category;
    private String chatId; // NULL для задач, добавленных вручную; иначе id чата AI-агента, из плана которого пришла задача

    public DayTask() {}

    public DayTask(Long id, String login, String date, String text, Double goalCount, Double progress, Integer position, String category, String chatId) {
        this.id = id;
        this.login = login;
        this.date = date;
        this.text = text;
        this.goalCount = goalCount;
        this.progress = progress;
        this.position = position;
        this.category = category;
        this.chatId = chatId;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getLogin() { return login; }
    public void setLogin(String login) { this.login = login; }
    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public Double getGoalCount() { return goalCount; }
    public void setGoalCount(Double goalCount) { this.goalCount = goalCount; }
    public Double getProgress() { return progress; }
    public void setProgress(Double progress) { this.progress = progress; }
    public Integer getPosition() { return position; }
    public void setPosition(Integer position) { this.position = position; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getChatId() { return chatId; }
    public void setChatId(String chatId) { this.chatId = chatId; }
}
