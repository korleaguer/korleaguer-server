package com.korleaguer.domain;

/** 선수·팀의 리그 단계. MLB 공식 팀 정보의 sport.id로 정한다. */
public enum Level {
    MLB,
    MINORS;

    /** sport.id 1이면 MLB, 11~16(트리플A ~ 루키)이면 MINORS, 그 밖(국가대표·독립리그 등)과 null은 null */
    public static Level fromSportId(Long sportId) {
        if (sportId == null) {
            return null;
        }
        if (sportId == 1) {
            return MLB;
        }
        return sportId >= 11 && sportId <= 16 ? MINORS : null;
    }
}
