package com.korleaguer.api.game;

import com.korleaguer.domain.Lineup;
import com.korleaguer.domain.Role;
import com.korleaguer.domain.SeasonType;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

import java.time.OffsetDateTime;

/**
 * 선수 × 경기 한 줄. summary는 api가 만든 한국어 문장이다.
 * played=false면 summary에 이유(결장, 경기 전, 기록 없음 등)가 들어간다. 점수는 경기 전이면 null이다.
 * photoUrl은 선수 공식 사진 주소, photoTeamAbbr은 그 사진 속 유니폼 팀 약어(웹 사진 원 바탕색, 사진이 없으면 null)이며 teamAbbr(그 경기 팀)과 다를 수 있다, opponentAbbr은 상대 팀 약어(웹의 상대 팀 로고용)이며 없으면 null이다.
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
        @Nullable String photoUrl,
        Role role,
        long gameId,
        OffsetDateTime startTimeKst,
        String teamKo,
        @Nullable String teamAbbr,
        String opponentKo,
        @Nullable String opponentAbbr,
        boolean home,
        @Nullable Integer teamScore,
        @Nullable Integer opponentScore,
        @Schema(allowableValues = {"W", "L", "T"}) @Nullable String result,
        String gameStatus,
        boolean played,
        String summary,
        @Nullable GameBatting batting,
        @Nullable GamePitching pitching,
        @Nullable Lineup lineup,
        @Nullable SeasonType seasonType,
        @Nullable String photoTeamAbbr) {
}
