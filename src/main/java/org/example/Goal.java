package org.example;

public class Goal {
    private String type;     // PUSHUPS, SQUATS, JAVA, ENGLISH
    private double target;   // цель
    private String login;    // чья цель

    public Goal(String type, double target, String login) {
        this.type = type;
        this.target = target;
        this.login = login;
    }

    public String getType() { return type; }
    public double getTarget() { return target; }
    public String getLogin() { return login; }
}