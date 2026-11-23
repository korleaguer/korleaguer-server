package com.korleaguer.api;

/** 그 선수의 경기가 아니다(우리 DB의 메이저 일정에도, 그 선수 마이너 기록에도 없다) */
public class GameNotFoundException extends RuntimeException {

    public GameNotFoundException(long playerId, long gamePk) {
        super("game not found: player " + playerId + ", game " + gamePk);
    }
}
