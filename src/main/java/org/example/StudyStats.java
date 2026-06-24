package org.example;

public class StudyStats {
    private String name;
    private double hours;
    private double javaHours;
    private double englishHours;

    public StudyStats(double hours, String name, double javaHours, double englishHours) {
        this.name = name;
        this.hours = hours;
        this.javaHours = javaHours;
        this.englishHours = englishHours;
    }

    public String getName() { return name; }
    public double getHours() { return hours; }
    public double getJavaHours() { return javaHours; }
    public double getEnglishHours() { return englishHours; }

    public void setName(String name) { this.name = name; }
    public void setHours(double hours) { this.hours = hours; }
    public void setJavaHours(double javaHours) { this.javaHours = javaHours; }
    public void setEnglishHours(double englishHours) { this.englishHours = englishHours; }
}
