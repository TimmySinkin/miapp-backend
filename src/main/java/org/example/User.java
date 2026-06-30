package org.example;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class User {
    private String login;
    private String password;
    private String name;
    private String email;
    private LocalDate createdAt;
    private List<DailyRecord> history;

    public User(String login, String password, String name) {
        this.login = login;
        this.password = password;
        this.name = name;
        this.createdAt = LocalDate.now();
        this.history = new ArrayList<>();
    }

    public User(String login, String password, String name, String email) {
        this(login, password, name);
        this.email = email;
    }

    public String getLogin() { return login; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public LocalDate getCreatedAt() { return createdAt; }
    public String getPassword() { return password; }

    public List<DailyRecord> getHistory() {
        return Collections.unmodifiableList(this.history);
    }

    public void setLogin(String login) { this.login = login; }
    public void setPassword(String password) { this.password = password; }
    public void setName(String name) { this.name = name; }
    public void setEmail(String email) { this.email = email; }

    public void clearHistory() { this.history.clear(); }
    public void addRecord(DailyRecord record) { this.history.add(record); }
    public void removeRecord(int index) { this.history.remove(index); }
    public boolean checkPassword(String input) { return this.password.equals(input); }

    public DailyRecord getTodayRecord() {
        LocalDate today = LocalDate.now();
        for (DailyRecord record : this.history) {
            if (record.getDate().equals(today)) {
                return record;
            }
        }
        DailyRecord newRecord = new DailyRecord();
        this.history.add(newRecord);
        return newRecord;
    }

    public DailyRecord findRecord(LocalDate date) {
        for (DailyRecord record : this.history) {
            if (record.getDate().equals(date)) {
                return record;
            }
        }
        return null;
    }
}
