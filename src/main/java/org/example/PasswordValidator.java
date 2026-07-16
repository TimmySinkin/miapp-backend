package org.example;

import java.util.regex.Pattern;

public class PasswordValidator {

    private static final Pattern ALLOWED_CHARS =
        Pattern.compile("^[A-Za-z0-9!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?~`]{6,}$");
    private static final Pattern HAS_DIGIT_OR_SPECIAL =
        Pattern.compile("[0-9!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?~`]");

    /**
     * Возвращает текст первой нарушенной проверки, либо null если пароль ок.
     * Правила: минимум 6 символов, начинается с заглавной латинской буквы,
     * только латиница/цифры/спецсимволы (без кириллицы и пробелов),
     * содержит хотя бы одну цифру или спецсимвол.
     */
    public static String validate(String password) {
        if (password == null || password.length() < 6) {
            return "Пароль должен содержать минимум 6 символов";
        }
        char first = password.charAt(0);
        if (first < 'A' || first > 'Z') {
            return "Пароль должен начинаться с заглавной латинской буквы";
        }
        if (!ALLOWED_CHARS.matcher(password).matches()) {
            return "Пароль может содержать только латинские буквы, цифры и спецсимволы (без кириллицы и пробелов)";
        }
        if (!HAS_DIGIT_OR_SPECIAL.matcher(password).find()) {
            return "Пароль должен содержать цифру или спецсимвол";
        }
        return null;
    }
}
