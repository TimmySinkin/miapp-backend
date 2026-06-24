package org.example;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.IOException;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.time.LocalDate;

public class MiniApp {

    public static void main(String[] args) {

        ViewService view = new ViewService();
        UserService userService = new UserService();

        // ─── ИНИЦИАЛИЗАЦИЯ БАЗЫ ДАННЫХ ───
        DatabaseService db = new DatabaseService();
        // ─────────────────────────────────

        Scanner in = new Scanner(System.in);

        List<User> users = new ArrayList<>();
        User currentUser = null;

        // ─── АВТОРИЗАЦИЯ ───
        while (true) {
            System.out.println("=== Добро пожаловать ===");
            System.out.println("1. Войти");
            System.out.println("2. Зарегистрироваться");
            System.out.println("0. Выход");

            int authChoice = in.nextInt();

            if (authChoice == 0) return;

            if (authChoice == 1) {
                System.out.print("Логин: ");
                String loginInput = in.next();
                System.out.print("Пароль: ");
                String passwordInput = in.next();

                currentUser = db.loadUserByLogin(loginInput);

                if (currentUser == null) {
                    System.out.println("Пользователь не найден. Попробуйте снова или зарегистрируйтесь.");
                    continue;
                }

                BCryptPasswordEncoder encoder =
                        new BCryptPasswordEncoder();

                if (!encoder.matches(passwordInput, currentUser.getPassword())) {
                    System.out.println("Неверный пароль.");
                    currentUser = null;
                    continue;
                }

                db.loadHistory(currentUser);
                break;

            } else if (authChoice == 2) {
                System.out.print("Логин: ");
                String loginInput = in.next();
                System.out.print("Пароль: ");
                String passwordInput = in.next();
                System.out.print("Имя: ");
                String nameInput = in.next();

                // ─── ХЭШИРУЕМ ПАРОЛЬ ───
                String hashedPassword = new BCryptPasswordEncoder().encode(passwordInput);

                currentUser = new User(loginInput, hashedPassword, nameInput);

                db.saveUser(currentUser);

                db.loadHistory(currentUser);

                System.out.println("Регистрация успешна!");
                System.out.println("Вы автоматически вошли в систему.");

                break;
            }
        }

        System.out.println("Добро пожаловать, " + currentUser.getName() + "!");

        while (true) {

            System.out.println("\n1. Добавить активность на день");
            System.out.println("2. Показать данные");
            System.out.println("3. Аналитика");
            System.out.println("4. Редактировать");
            System.out.println("5. Удалить");
            System.out.println("6. Найти");
            System.out.println("7. Сохранить");
            System.out.println("0. Выход");

            int choice = in.nextInt();

            switch (choice) {

                case 1:
                    System.out.println("1. Тренировка");
                    System.out.println("2. Учёба");
                    int type = in.nextInt();

                    if (type == 1) {
                        System.out.print("Отжимания: ");
                        int pushUps = in.nextInt();
                        System.out.print("Приседания: ");
                        int squats = in.nextInt();
                        System.out.print("Время: ");
                        double hours = in.nextDouble();

                        userService.addWorkout(currentUser, pushUps, squats, hours);
                        System.out.println("Тренировка добавлена!");

                    } else if (type == 2) {
                        System.out.print("English часы: ");
                        double englishHours = in.nextDouble();
                        System.out.print("Java-code часы: ");
                        double javaHours = in.nextDouble();

                        userService.addStudy(currentUser, javaHours, englishHours);
                        System.out.println("Общее время: " + (javaHours + englishHours) + " ч.");
                        System.out.println("Учёба добавлена!");

                    } else {
                        System.out.println("Введенное значение отсутствует");
                    }
                    break;

                case 2:
                    view.showHistory(currentUser);
                    break;

                case 3:
                if (currentUser.getHistory().isEmpty()) {
                    System.out.println("Нет данных");
                    break;
                }

                    AnalyticsService analytics = new AnalyticsService();
                    analytics.showWorkoutStats(currentUser);
                    analytics.showStudyStats(currentUser);
                    analytics.showBestWorkoutDay(currentUser);
                    analytics.showBestStudyDay(currentUser);
                    analytics.showTotalTime(currentUser);
                    analytics.showWeeklyAverage(currentUser);
                    analytics.showMonthlyAverage(currentUser);
                break;

                case 4:
                    if (currentUser.getHistory().isEmpty()) {
                        System.out.println("Нет данных");
                        break;
                    }
                    for (int i = 0; i < currentUser.getHistory().size(); i++) {
                        System.out.println((i + 1) + ". Запись за дату: " + currentUser.getHistory().get(i).getDate());
                    }
                    System.out.print("Введите номер дня для редактирования: ");
                    int dayIndex = in.nextInt() - 1;

                    if (dayIndex < 0 || dayIndex >= currentUser.getHistory().size()) {
                        System.out.println("Неверный индекс дня");
                        break;
                    }

                    DailyRecord selectedRecord = currentUser.getHistory().get(dayIndex);
                    System.out.println("Что вы хотите изменить? 1. Тренировку, 2. Учёбу");
                    int editType = in.nextInt();

                    if (editType == 1) {
                        if (selectedRecord.getWorkouts().isEmpty()) { System.out.println("Нет тренировок"); break; }
                        for (int i = 0; i < selectedRecord.getWorkouts().size(); i++) {
                            System.out.println((i + 1) + ". " + selectedRecord.getWorkouts().get(i).getName());
                        }
                        System.out.print("Выберите номер тренировки: ");
                        int wIndex = in.nextInt() - 1;
                        WorkoutStats w = selectedRecord.getWorkouts().get(wIndex);
                        in.nextLine();
                        System.out.print("Новое имя: "); w.setName(in.nextLine());
                        System.out.print("Новое время: "); w.setHours(in.nextDouble());
                        System.out.print("Новые отжимания: "); w.setPushUps(in.nextInt());
                        System.out.print("Новые приседания: "); w.setSquats(in.nextInt());
                        System.out.println("Обновлено!");

                    } else if (editType == 2) {
                        if (selectedRecord.getStudies().isEmpty()) { System.out.println("Нет записей учёбы"); break; }
                        for (int i = 0; i < selectedRecord.getStudies().size(); i++) {
                            System.out.println((i + 1) + ". " + selectedRecord.getStudies().get(i).getName());
                        }
                        System.out.print("Выберите номер записи учёбы: ");
                        int sIndex = in.nextInt() - 1;
                        StudyStats s = selectedRecord.getStudies().get(sIndex);
                        in.nextLine();
                        System.out.print("Новое имя: "); s.setName(in.nextLine());
                        System.out.print("Новое время-Java: ");
                        double newJavaHours = in.nextDouble();
                        System.out.print("Новые время-English: ");
                        double newEnglishHours = in.nextDouble();

                        double newHours = newJavaHours + newEnglishHours;
                        System.out.println("Общее время посчитано автоматически: " + newHours + " ч.");

                        s.setJavaHours(newJavaHours);
                        s.setEnglishHours(newEnglishHours);
                        s.setHours(newHours);
                        System.out.println("Обновлено!");
                    }
                    break;

                case 5:
                    if (currentUser.getHistory().isEmpty()) {
                        System.out.println("Нет данных");
                        break;
                    }

                    for (int i = 0; i < currentUser.getHistory().size(); i++) {
                        System.out.println((i + 1) + ". " + currentUser.getHistory().get(i).getDate());
                    }
                    System.out.print("Введите номер дня: ");
                    int deleteDayIndex = in.nextInt() - 1;

                    if (deleteDayIndex < 0 || deleteDayIndex >= currentUser.getHistory().size()) {
                        System.out.println("Неверный индекс");
                        break;
                    }

                    DailyRecord recordToDelete = currentUser.getHistory().get(deleteDayIndex);

                    System.out.println("Что удалить?");
                    System.out.println("1. День полностью");
                    System.out.println("2. Тренировку");
                    System.out.println("3. Учёбу");
                    int deleteType = in.nextInt();

                    if (deleteType == 1) {
                        currentUser.removeRecord(deleteDayIndex);
                        System.out.println("День удалён!");

                    } else if (deleteType == 2) {
                        if (recordToDelete.getWorkouts().isEmpty()) { System.out.println("Нет тренировок"); break; }
                        for (int i = 0; i < recordToDelete.getWorkouts().size(); i++) {
                            System.out.println((i + 1) + ". " + recordToDelete.getWorkouts().get(i).getName());
                        }
                        System.out.print("Введите номер тренировки: ");
                        int deleteWorkoutIndex = in.nextInt() - 1;
                        recordToDelete.removeWorkout(deleteWorkoutIndex);
                        System.out.println("Тренировка удалена!");

                    } else if (deleteType == 3) {
                        if (recordToDelete.getStudies().isEmpty()) { System.out.println("Нет записей учёбы"); break; }
                        for (int i = 0; i < recordToDelete.getStudies().size(); i++) {
                            System.out.println((i + 1) + ". " + recordToDelete.getStudies().get(i).getName());
                        }
                        System.out.print("Введите номер записи учёбы: ");
                        int deleteStudyIndex = in.nextInt() - 1;
                        recordToDelete.removeStudy(deleteStudyIndex);
                        System.out.println("Запись учёбы удалена!");

                    } else {
                        System.out.println("Неверный выбор");
                    }
                    break;

                case 6:
                    System.out.print("Введите дату (ГГГГ-ММ-ДД): ");
                    String dateInput = in.next();

                    try {
                        LocalDate searchDate = LocalDate.parse(dateInput);
                        DailyRecord foundRecord = currentUser.findRecord(searchDate);
                        view.showSearchResult(foundRecord);

                    } catch (DateTimeParseException e) {
                        System.out.println("Неверный формат даты. Пример: 2026-06-12");
                    }
                    break;

                case 7:
                    db.saveUser(currentUser);
                    db.saveWorkouts(currentUser);
                    db.saveStudies(currentUser);
                    break;

                case 0:
                    System.out.println("Выход...");
                    in.close();
                    return;

                default:
                    System.out.println("Неверный выбор");
            }
        }
    }
}