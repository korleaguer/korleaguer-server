package com.korleaguer.api;

import com.korleaguer.domain.Level;
import com.korleaguer.domain.Role;

import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/players/{id}. 타자면 pitching이, 투수면 hitting이 null이다.
 * moves는 MLB 공식 API의 이동 기록 중 팬에게 의미 있는 것만, 최신순이다(없으면 빈 목록).
 */
public record PlayerPageResponse(
        String dataMode,
        boolean statsAvailable,
        int season,
        PlayerInfo player,
        HitterSeason hitting,
        PitcherSeason pitching,
        List<GameLine> games,
        List<Move> moves) {

    /**
     * photoUrl·mlbUrl·careerKo는 명단(baseball.yml) 값이라 매칭 전에도 나올 수 있다.
     * 생년월일·키·몸무게·투타·등번호·데뷔 연도는 balldontlie 값이라 매칭 전이거나 값이 없으면 null이다.
     * age는 생년월일이 있으면 한국 시간 오늘 기준 만 나이, 없으면 balldontlie가 준 나이다.
     * batsThrowsKo는 "우투좌타"처럼 투구 먼저 쓴다.
     */
    public record PlayerInfo(
            long id,
            String nameKo,
            String photoUrl,
            String mlbUrl,
            String nameEn,
            Role role,
            String position,
            String positionKo,
            String teamKo,
            String teamAbbr,
            String teamEn,
            String birthPlace,
            String jersey,
            LocalDate birthDate,
            Integer age,
            Integer heightCm,
            Integer weightKg,
            String batsThrowsKo,
            Integer debutYear,
            String careerKo) {
    }

    /**
     * 이동 기록 한 줄. labelKo는 서버가 typeCode(와 설명 글)로 만든 짧은 한국어 이름이다(예: "메이저 콜업").
     * fromTeam·toTeam은 MLB 구단이면 한글 이름(예: "미네소타"), 마이너 팀 등은 MLB가 준 영어 이름 그대로이고 없으면 null.
     * toLevel은 옮겨 간 팀의 단계(MLB / MINORS), 모르면 null이다.
     */
    public record Move(
            LocalDate date,
            String typeCode,
            String labelKo,
            String fromTeam,
            String toTeam,
            Level toLevel) {
    }
}
