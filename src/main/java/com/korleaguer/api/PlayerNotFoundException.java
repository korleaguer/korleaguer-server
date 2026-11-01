package com.korleaguer.api;

public class PlayerNotFoundException extends RuntimeException {

    public PlayerNotFoundException(long id) {
        super("player not found: " + id);
    }
}
