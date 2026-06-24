package org.example;

public class RegisterRequest {
    private String login;
    private String password;
    private String name;

    public String getLogin() { return login; }
    public String getPassword() { return password; }
    public String getName() { return name; }
    public void setLogin(String login) { this.login = login; }
    public void setPassword(String password) { this.password = password; }
    public void setName(String name) { this.name = name; }
}
