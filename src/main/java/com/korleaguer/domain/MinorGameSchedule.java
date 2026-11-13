package com.korleaguer.domain;

import jakarta.persistence.Embeddable;

import java.time.Instant;

/**
 * 마이너 경기 한 개의 일정 값(MLB schedule?gamePks=로 받는다). 모든 칸이 null이면 JPA가 이것 자체를 null로 읽는다(아직 묻지 않음).
 * startTimeUtc는 시작 시각, statusState는 support.GameStatuses 값(final 등)이다.
 * teamScore·opponentScore는 그 경기 선수 팀·상대 팀 점수(경기 전이면 null),
 * teamPlace·opponentPlace는 팀 지명(MLB franchiseName, 예: "Quad Cities")이다. 한국어 이름이 없는 팀에 쓴다.
 * 선수 팀이 홈인지 원정인지 모르면 점수·지명은 비운다(뒤바뀐 점수를 남기지 않게).
 */
@Embeddable
public record MinorGameSchedule(
        Instant startTimeUtc,
        String statusState,
        Integer teamScore,
        Integer opponentScore,
        String teamPlace,
        String opponentPlace) {

    /** 일정을 물었지만 응답에 없었던 경기(물었다는 표시만 남긴다. 상태는 모름) */
    public static MinorGameSchedule notFound() {
        return new MinorGameSchedule(null, "unknown", null, null, null, null);
    }

    /** 끝난 경기이고 점수를 안다(다시 묻지 않는다) */
    public boolean isSettled() {
        return Game.STATUS_FINAL.equals(statusState) && teamScore != null && opponentScore != null;
    }
}
