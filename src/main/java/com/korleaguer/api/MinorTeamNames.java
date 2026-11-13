package com.korleaguer.api;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 마이너 팀 화면 이름. 한국어 이름(minor-teams.yml)이 있으면 그것, 없으면 영어 지명(MLB franchiseName), 그것도 없으면
 * 영어 팀 이름이다. 같은 번호가 두 번이거나 이름이 비면 앱이 뜰 때 실패한다(사람이 적은 값이라 바로 알게).
 */
@Component
class MinorTeamNames {

    private final Map<Long, String> korean = new HashMap<>();

    MinorTeamNames(MinorTeamProperties properties) {
        for (MinorTeamProperties.Team team : properties.teams()) {
            if (team.id() == null || team.nameKo() == null || team.nameKo().isBlank()) {
                throw new IllegalStateException("minor-teams.yml: id and name-ko are required (" + team + ")");
            }
            if (korean.put(team.id(), team.nameKo().strip()) != null) {
                throw new IllegalStateException("minor-teams.yml: team " + team.id() + " is listed twice");
            }
        }
    }

    /**
     * @param teamId   MLB 팀 번호(모르면 null)
     * @param place    영어 지명(MLB franchiseName, 모르면 null)
     * @param fullName 영어 팀 이름(gameLog의 team.name)
     */
    String name(Long teamId, String place, String fullName) {
        String name = teamId == null ? null : korean.get(teamId);
        if (name != null) {
            return name;
        }
        return place == null || place.isBlank() ? fullName : place;
    }
}
