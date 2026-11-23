package com.korleaguer.api.player;

public class PlayerNotFoundException extends RuntimeException {

    public PlayerNotFoundException(long id) {
        super("player not found: " + id);
    }
}
