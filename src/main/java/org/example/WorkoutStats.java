package org.example;

public class WorkoutStats {
    private String name;
    private double hours;
    private int pushUps;
    private int squats;

    public WorkoutStats(int pushUps, int squats, double hours, String name) {
        this.name = name;
        this.hours = hours;
        this.pushUps = pushUps;
        this.squats = squats;
    }

    public String getName() { return name; }
    public double getHours() { return hours; }
    public int getPushUps() { return pushUps; }
    public int getSquats() { return squats; }

    public void setName(String name) { this.name = name; }
    public void setHours(double hours) { this.hours = hours; }
    public void setPushUps(int pushUps) { this.pushUps = pushUps; }
    public void setSquats(int squats) { this.squats = squats; }
}