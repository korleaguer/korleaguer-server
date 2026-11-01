package com.korleaguer.domain;

public enum Role {
    HITTER,
    PITCHER;

    // position 값에 "Pitcher"가 들어 있으면 투수다.
    public static Role fromPosition(String position) {
        return position != null && position.contains("Pitcher") ? PITCHER : HITTER;
    }
}
