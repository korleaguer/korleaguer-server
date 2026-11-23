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
    /** 옛 기록용. 선수 페이지를 열 때 그 자리에서 시즌을 받던 수집(이슈 #231에서 없앰). 새로 만들지 않고, 저장된 옛 줄을 읽을 수 있게만 둔다 */
    PLAYER_SEASON
}
