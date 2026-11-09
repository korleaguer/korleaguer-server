package com.korleaguer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * MLB 선수. 호출에 따라 채워진 칸이 다르다(이동 기록·박스스코어 안에서는 id·fullName 정도만 있다).
 * currentTeam은 hydrate=currentTeam으로 받을 때만 있고, 소속이 없으면(FA 등) null이다. 마이너 팀이면 parentOrgId가 있다.
 * height는 "6' 0\"" 같은 문자열, weight는 파운드 정수, birthDate·mlbDebutDate는 "yyyy-MM-dd"다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlbPerson(
        Long id,
        String fullName,
        String primaryNumber,
        String birthDate,
        Integer currentAge,
        String birthCity,
        String birthStateProvince,
        String birthCountry,
        String height,
        Integer weight,
        Boolean active,
        Position primaryPosition,
        Hand batSide,
        Hand pitchHand,
        String mlbDebutDate,
        MlbTeam currentTeam) {

    /** 번호·이름·지금 팀만 있는 선수(테스트·샘플용) */
    public static MlbPerson of(Long id, String fullName, MlbTeam currentTeam) {
        return new MlbPerson(id, fullName, null, null, null, null, null, null, null, null, null, null, null, null,
                null, currentTeam);
    }

    /** 주 포지션. abbreviation 예: P, C, 1B, 2B, 3B, SS, LF, CF, RF, OF, DH, TWP */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Position(String code, String name, String type, String abbreviation) {
    }

    /** 타격·투구 손. code: L / R / S(양쪽), description: Left / Right / Switch */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Hand(String code, String description) {
    }
}
