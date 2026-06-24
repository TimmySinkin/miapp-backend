package org.example;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

public class AnalyticsService {

    public void showWorkoutStats(User user) {
        int totalPushUps = user.getHistory().stream()
                .flatMap(r -> r.getWorkouts().stream())
                .mapToInt(w -> w.getPushUps())
                .sum();

        int totalSquats = user.getHistory().stream()
                .flatMap(r -> r.getWorkouts().stream())
                .mapToInt(w -> w.getSquats())
                .sum();

        double totalWorkoutHours = user.getHistory().stream()
                .flatMap(r -> r.getWorkouts().stream())
                .mapToDouble(w -> w.getHours())
                .sum();

        int workoutCount = (int) user.getHistory().stream()
                .flatMap(r -> r.getWorkouts().stream())
                .count();

        if (workoutCount == 0) {
            System.out.println("Нет данных о тренировках");
            return;
        }

        System.out.println("\n=== Аналитика тренировок ===");
        System.out.println("Сумма отжиманий   : " + totalPushUps + " раз");
        System.out.println("Сумма приседаний  : " + totalSquats + " раз");
        System.out.println("Суммарное время   : " + totalWorkoutHours + " ч.");
        System.out.println("Средние отжимания : " + (double) totalPushUps / workoutCount + " раз");
        System.out.println("Средние приседания: " + (double) totalSquats / workoutCount + " раз");
        System.out.println("Среднее время     : " + totalWorkoutHours / workoutCount + " ч.");
    }

    public void showStudyStats(User user) {
        double totalJavaHours = user.getHistory().stream()
                .flatMap(r -> r.getStudies().stream())
                .mapToDouble(s -> s.getJavaHours())
                .sum();

        double totalEnglishHours = user.getHistory().stream()
                .flatMap(r -> r.getStudies().stream())
                .mapToDouble(s -> s.getEnglishHours())
                .sum();

        double totalStudyHours = user.getHistory().stream()
                .flatMap(r -> r.getStudies().stream())
                .mapToDouble(s -> s.getHours())
                .sum();

        int studyCount = (int) user.getHistory().stream()
                .flatMap(r -> r.getStudies().stream())
                .count();

        if (studyCount == 0) {
            System.out.println("Нет данных об учёбе");
            return;
        }

        System.out.println("\n=== Аналитика учёбы ===");
        System.out.println("Суммарное время Java   : " + totalJavaHours + " ч.");
        System.out.println("Суммарное время English: " + totalEnglishHours + " ч.");
        System.out.println("Общее время учёбы      : " + totalStudyHours + " ч.");
        System.out.println("Средние Java часы      : " + totalJavaHours / studyCount + " ч.");
        System.out.println("Средние English часы   : " + totalEnglishHours / studyCount + " ч.");
        System.out.println("Среднее время учёбы    : " + totalStudyHours / studyCount + " ч.");
    }

    public void showBestWorkoutDay(User user) {
        user.getHistory().stream()
                .filter(r -> !r.getWorkouts().isEmpty())
                .max((r1, r2) -> {
                    int pushUps1 = r1.getWorkouts().stream().mapToInt(w -> w.getPushUps()).sum();
                    int pushUps2 = r2.getWorkouts().stream().mapToInt(w -> w.getPushUps()).sum();
                    return Integer.compare(pushUps1, pushUps2);
                })
                .ifPresentOrElse(
                        bestDay -> {
                            int bestPushUps = bestDay.getWorkouts().stream()
                                    .mapToInt(w -> w.getPushUps()).sum();
                            System.out.println("\n=== Лучший день по отжиманиям ===");
                            System.out.println("Дата     : " + bestDay.getDate());
                            System.out.println("Отжимания: " + bestPushUps + " раз");
                        },
                        () -> System.out.println("Нет данных о тренировках")
                );
    }

    public void showBestStudyDay(User user) {
        user.getHistory().stream()
                .filter(r -> !r.getStudies().isEmpty())
                .max((r1, r2) -> {
                    double hours1 = r1.getStudies().stream().mapToDouble(s -> s.getHours()).sum();
                    double hours2 = r2.getStudies().stream().mapToDouble(s -> s.getHours()).sum();
                    return Double.compare(hours1, hours2);
                })
                .ifPresentOrElse(
                        bestDay -> {
                            double bestHours = bestDay.getStudies().stream()
                                    .mapToDouble(s -> s.getHours()).sum();
                            System.out.println("\n=== Лучший день по учёбе ===");
                            System.out.println("Дата          : " + bestDay.getDate());
                            System.out.println("Суммарное время: " + bestHours + " ч.");
                        },
                        () -> System.out.println("Нет данных об учёбе")
                );
    }

    public void showTotalTime(User user) {
        double totalWorkoutHours = user.getHistory().stream()
                .flatMap(r -> r.getWorkouts().stream())
                .mapToDouble(w -> w.getHours())
                .sum();

        double totalStudyHours = user.getHistory().stream()
                .flatMap(r -> r.getStudies().stream())
                .mapToDouble(s -> s.getHours())
                .sum();

        System.out.println("\n=== Общее время за всё время ===");
        System.out.println("Тренировки : " + totalWorkoutHours + " ч.");
        System.out.println("Учёба      : " + totalStudyHours + " ч.");
        System.out.println("Итого      : " + (totalWorkoutHours + totalStudyHours) + " ч.");
    }

    public void showWeeklyAverage(User user) {
        LocalDate weekAgo = LocalDate.now().minusDays(7);

        List<DailyRecord> weekRecords = user.getHistory().stream()
                .filter(r -> r.getDate().isAfter(weekAgo))
                .collect(Collectors.toList());

        System.out.println("\n=== Среднее за последние 7 дней ===");
        if (weekRecords.isEmpty()) {
            System.out.println("Нет данных за последнюю неделю");
            return;
        }

        double totalWorkoutHours = weekRecords.stream()
                .flatMap(r -> r.getWorkouts().stream())
                .mapToDouble(w -> w.getHours())
                .sum();

        double totalStudyHours = weekRecords.stream()
                .flatMap(r -> r.getStudies().stream())
                .mapToDouble(s -> s.getHours())
                .sum();

        int days = weekRecords.size();
        System.out.println("Активных дней    : " + days);
        System.out.println("Среднее тренировки: " + totalWorkoutHours / days + " ч.");
        System.out.println("Среднее учёбы    : " + totalStudyHours / days + " ч.");
    }

    public void showMonthlyAverage(User user) {
        LocalDate monthAgo = LocalDate.now().minusDays(30);

        List<DailyRecord> monthRecords = user.getHistory().stream()
                .filter(r -> r.getDate().isAfter(monthAgo))
                .collect(Collectors.toList());

        System.out.println("\n=== Среднее за последние 30 дней ===");
        if (monthRecords.isEmpty()) {
            System.out.println("Нет данных за последний месяц");
            return;
        }

        double totalWorkoutHours = monthRecords.stream()
                .flatMap(r -> r.getWorkouts().stream())
                .mapToDouble(w -> w.getHours())
                .sum();

        double totalStudyHours = monthRecords.stream()
                .flatMap(r -> r.getStudies().stream())
                .mapToDouble(s -> s.getHours())
                .sum();

        int days = monthRecords.size();
        System.out.println("Активных дней    : " + days);
        System.out.println("Среднее тренировки: " + totalWorkoutHours / days + " ч.");
        System.out.println("Среднее учёбы    : " + totalStudyHours / days + " ч.");
    }
}