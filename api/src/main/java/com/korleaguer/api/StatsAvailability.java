package com.korleaguer.api;

/**
 * 선수 기록을 받는 상태인지. MLB 공식 API(와 샘플)는 경기별 선수 기록을 늘 주므로 true다.
 * 응답의 statsAvailable 필드는 화면 호환 때문에 남겨 둔다. 홈·선수 페이지(screen)와 내 선수(card)가 같은 값을 쓴다.
 */
public final class StatsAvailability {

    private StatsAvailability() {
    }

    public static boolean statsAvailable() {
        return true;
    }
}
