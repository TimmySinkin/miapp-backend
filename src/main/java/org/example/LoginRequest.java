package org.example;

public class LoginRequest {
    private String login;
    private String password;
    private boolean rememberMe;

    public String getLogin() { return login; }
    public String getPassword() { return password; }
    public boolean isRememberMe() { return rememberMe; }
    public void setLogin(String login) { this.login = login; }
    public void setPassword(String password) { this.password = password; }
    public void setRememberMe(boolean rememberMe) { this.rememberMe = rememberMe; }
}