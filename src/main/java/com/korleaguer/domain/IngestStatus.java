package com.korleaguer.domain;

public enum IngestStatus {
    RUNNING,
    SUCCESS,
    /** 받을 경기가 없어서 선수 기록은 부르지 않았다. */
    NO_GAMES,
    /** 경기는 받았지만 선수 기록은 요금제(401/403) 때문에 받지 못했다. */
    STATS_UNAVAILABLE,
    FAILED
}
