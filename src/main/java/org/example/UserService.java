package org.example;

public class UserService {

    public void addWorkout(User user, int pushUps, int squats, double hours) {
        DailyRecord todayRecord = user.getTodayRecord();
        WorkoutStats workout = new WorkoutStats(pushUps, squats, hours, user.getName());
        todayRecord.addWorkout(workout);
    }

    public void addStudy(User user, double javaHours, double englishHours) {
        DailyRecord todayRecord = user.getTodayRecord();
        double hours = javaHours + englishHours;
        StudyStats study = new StudyStats(hours, user.getName(), javaHours, englishHours);
        todayRecord.addStudy(study);
    }
}
