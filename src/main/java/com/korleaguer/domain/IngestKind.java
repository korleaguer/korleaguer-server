package com.korleaguer.domain;

public enum IngestKind {
    /** 명단 선수의 프로필·소속·단계·이동 기록 */
    PLAYERS,
    /** 명단 선수 전원의 한 시즌 다시 받기 */
    SEASON,
    /** 미국 날짜 하루(와 전날)의 우리 팀 경기 */
    GAMES,
    /** 스케줄·앱 시작 때 도는 따라잡기(필요한 것만) */
    SCHEDULED,
    /** 선수 페이지를 열었는데 그 선수 시즌이 비어 있어 받은 것 */
    PLAYER_SEASON
}
