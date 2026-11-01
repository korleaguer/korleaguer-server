package com.korleaguer.api;

import com.korleaguer.domain.Role;

import java.time.LocalDate;
import java.util.List;

/** GET /api/players/{id}. 타자면 pitching이, 투수면 hitting이 null이다. */
public record PlayerPageResponse(
        String dataMode,
        boolean statsAvailable,
        int season,
        PlayerInfo player,
        HitterSeason hitting,
        PitcherSeason pitching,
        List<GameLine> games) {

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
}
