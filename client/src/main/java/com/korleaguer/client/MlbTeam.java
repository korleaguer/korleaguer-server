package com.korleaguer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * MLB 팀. 호출에 따라 채워진 칸이 다르다.
 * 이동 기록·선수의 currentTeam은 id·name 정도만, 팀 조회(/teams/{id})는 sport까지 준다.
 * sport.id: 1 = MLB, 11~16 = 마이너(트리플A ~ 루키). parentOrgId는 마이너 팀의 MLB 구단 번호다.
 * franchiseName은 팀의 지명(예: "Oklahoma City", "Quad Cities", 루키 팀은 "ACL Dodgers"처럼 이름 전체)으로,
 * 팀 조회와 일정의 hydrate=team에만 온다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlbTeam(Long id, String name, String abbreviation, Sport sport, Long parentOrgId, String franchiseName) {

    /** 지명(franchiseName) 없이 */
    public MlbTeam(Long id, String name, String abbreviation, Sport sport, Long parentOrgId) {
        this(id, name, abbreviation, sport, parentOrgId, null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Sport(Long id, String name) {
    }

    /** sport가 없으면 null */
    public Long sportId() {
        return sport == null ? null : sport.id();
    }
}
