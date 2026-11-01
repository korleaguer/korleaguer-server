package com.korleaguer.api;

import com.korleaguer.domain.Role;

import java.time.OffsetDateTime;

/**
 * 선수 × 경기 한 줄. summary는 api가 만든 한국어 문장이다.
 * played=false면 summary에 이유(결장, 경기 전, 기록 없음 등)가 들어간다. 점수는 경기 전이면 null이다.
 * photoUrl은 선수 공식 사진 주소, opponentAbbr은 상대 팀 약어(웹의 상대 팀 로고용)이며 없으면 null이다.
 */
public record GameLine(
        long playerId,
        String nameKo,
        String photoUrl,
        Role role,
        long gameId,
        OffsetDateTime startTimeKst,
        String teamKo,
        String teamAbbr,
        String opponentKo,
        String opponentAbbr,
        boolean home,
        Integer teamScore,
        Integer opponentScore,
        String result,
        String gameStatus,
        boolean played,
        String summary) {
}
