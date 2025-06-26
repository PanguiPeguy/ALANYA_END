package Client.service;

import java.io.Serializable;

public class UserStatus implements Serializable {
    private static final long serialVersionUID = 1L;
    private String username;
    private boolean isOnline;

    public UserStatus(String username, boolean isOnline) {
        this.username = username;
        this.isOnline = isOnline;
    }

    public String getUsername() {
        return username;
    }

    public boolean isOnline() {
        return isOnline;
    }

    @Override
    public String toString() {
        return username + (isOnline ? " (Online)" : " (Offline)");
    }
}