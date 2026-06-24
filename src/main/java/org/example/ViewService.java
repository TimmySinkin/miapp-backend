package org.example;

public class ViewService {

    public void showRecord(DailyRecord record) {
        System.out.println("\n=== ДАТА: " + record.getDate() + " ===");

        // ─── ТРЕНИРОВКИ ───
        System.out.println("--- Тренировки ---");
        if (record.getWorkouts().isEmpty()) {
            System.out.println("Нет тренировок");
        } else {
            int dayPushUps = 0;
            int daySquats = 0;
            double dayWorkoutHours = 0;

            for (WorkoutStats w : record.getWorkouts()) {
                dayPushUps += w.getPushUps();
                daySquats += w.getSquats();
                dayWorkoutHours += w.getHours();
            }

            System.out.println("Всего тренировок : " + record.getWorkouts().size() + " шт.");
            System.out.println("Суммарное время  : " + dayWorkoutHours + " ч.");
            System.out.println("Всего отжиманий  : " + dayPushUps + " раз");
            System.out.println("Всего приседаний : " + daySquats + " раз");
        }

        // ─── УЧЁБА ───
        System.out.println("--- Учёба ---");
        if (record.getStudies().isEmpty()) {
            System.out.println("Нет записей об учёбе");
        } else {
            double dayStudyHours = 0;
            double dayJavaHours = 0;
            double dayEnglishHours = 0;

            for (StudyStats s : record.getStudies()) {
                dayStudyHours += s.getHours();
                dayJavaHours += s.getJavaHours();
                dayEnglishHours += s.getEnglishHours();
            }

            if (dayJavaHours + dayEnglishHours > dayStudyHours) {
                System.out.println("⚠ Внимание: данные за этот день повреждены!");
            } else {
                System.out.println("Всего сессий     : " + record.getStudies().size() + " шт.");
                System.out.println("Суммарное время  : " + dayStudyHours + " ч.");
                System.out.println("Java часы        : " + dayJavaHours + " ч.");
                System.out.println("English часы     : " + dayEnglishHours + " ч.");
            }
        }
    }

    public void showHistory(User user) {
        if (user.getHistory().isEmpty()) {
            System.out.println("Нет данных");
            return;
        }
        for (DailyRecord record : user.getHistory()) {
            showRecord(record);
        }
    }

    public void showSearchResult(DailyRecord record) {
        if (record == null) {
            System.out.println("Запись не найдена");
            return;
        }
        showRecord(record);
    }
}
