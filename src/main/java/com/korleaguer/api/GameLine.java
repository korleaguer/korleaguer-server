package com.korleaguer.api;

import com.korleaguer.domain.Lineup;
import com.korleaguer.domain.Role;
import com.korleaguer.domain.SeasonType;

import java.time.OffsetDateTime;

/**
 * 선수 × 경기 한 줄. summary는 api가 만든 한국어 문장이다.
 * played=false면 summary에 이유(결장, 경기 전, 기록 없음 등)가 들어간다. 점수는 경기 전이면 null이다.
 * photoUrl은 선수 공식 사진 주소, opponentAbbr은 상대 팀 약어(웹의 상대 팀 로고용)이며 없으면 null이다.
 * batting·pitching은 그 경기 숫자(그래프용)로, 뛴 경기에 그 기록이 있을 때만 나오고 아니면 null이다.
 * lineup은 선발(STARTER)·교체(SUB)다. 뛰지 않았거나 아직 모르면 null이다.
 * seasonType(이슈 #190)은 그 경기가 정규시즌(REGULAR)인지 포스트시즌(POSTSEASON)인지다. 뛴 경기·결장 줄 모두 그 경기의 종류
 * (game.season_type)이다. 경기 줄은 이 두 종류의 경기로만 만들어서(GameLineAssembler) 메이저 줄은 늘 값이 있다. 내 선수 카드의
 * 마이너 경기 줄은 정규시즌 경기만 남겨 저장해서(MlbMinorGameLog.isRegularSeason) 늘 REGULAR다. 웹이 포스트시즌을 가를 때 쓴다
 * (타격 흐름·경기 달력).
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
        String summary,
        GameBatting batting,
        GamePitching pitching,
        Lineup lineup,
        SeasonType seasonType) {
}
