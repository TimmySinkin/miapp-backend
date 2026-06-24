package org.example;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DailyRecord {
    private LocalDate date;
    private List<WorkoutStats> workouts;
    private List<StudyStats> studies;

    public DailyRecord() {
        this.date = LocalDate.now();
        this.workouts = new ArrayList<>();
        this.studies = new ArrayList<>();
    }

    public DailyRecord(LocalDate date) {
        this.date = date;
        this.workouts = new ArrayList<>();
        this.studies = new ArrayList<>();
    }

    public void addWorkout(WorkoutStats workout) { this.workouts.add(workout); }
    public void addStudy(StudyStats study) { this.studies.add(study); }
    public LocalDate getDate() { return this.date; }

    public List<WorkoutStats> getWorkouts() {
        return Collections.unmodifiableList(this.workouts);
    }

    public List<StudyStats> getStudies() {
        return Collections.unmodifiableList(this.studies);
    }

    public void removeWorkout(int index) { this.workouts.remove(index); }
    public void removeStudy(int index) { this.studies.remove(index); }
}
