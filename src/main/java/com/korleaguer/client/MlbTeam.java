package com.korleaguer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * MLB 팀. 호출에 따라 채워진 칸이 다르다.
 * 이동 기록·선수의 currentTeam은 id·name 정도만, 팀 조회(/teams/{id})는 sport까지 준다.
 * sport.id: 1 = MLB, 11~16 = 마이너(트리플A ~ 루키). parentOrgId는 마이너 팀의 MLB 구단 번호다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlbTeam(Long id, String name, String abbreviation, Sport sport, Long parentOrgId) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Sport(Long id, String name) {
    }

    /** sport가 없으면 null */
    public Long sportId() {
        return sport == null ? null : sport.id();
    }
}
