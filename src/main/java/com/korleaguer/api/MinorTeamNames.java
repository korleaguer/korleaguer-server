package com.korleaguer.api;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 마이너 팀 화면 이름. 한국어 이름(minor-teams.yml)이 있으면 그것, 없으면 영어 지명(MLB franchiseName), 그것도 없으면
 * 영어 팀 이름이다. 이동 기록에는 단계도 붙인다(moveName). 같은 번호가 두 번이거나 이름이 비거나 단계를 모르는 값이면
 * 앱이 뜰 때 실패한다(사람이 적은 값이라 바로 알게).
 */
@Component
class MinorTeamNames {

    private final Map<Long, String> korean = new HashMap<>();
    /** 팀 번호 → 단계 화면 이름(트리플A·더블A·하이싱글A·싱글A·루키). yml에 단계를 적은 팀만 있다 */
    private final Map<Long, String> levels = new HashMap<>();

    MinorTeamNames(MinorTeamProperties properties) {
        for (MinorTeamProperties.Team team : properties.teams()) {
            if (team.id() == null || team.nameKo() == null || team.nameKo().isBlank()) {
                throw new IllegalStateException("minor-teams.yml: id and name-ko are required (" + team + ")");
            }
            if (korean.put(team.id(), team.nameKo().strip()) != null) {
                throw new IllegalStateException("minor-teams.yml: team " + team.id() + " is listed twice");
            }
            if (team.level() != null && !team.level().isBlank()) {
                // 단계는 이동 기록 팀 이름에 그대로 보이므로, 오타(AAAA 등)로 조용히 빠지지 않게 아는 약어만 받는다
                String levelKo = MinorRecords.LEVEL_KO.get(team.level().strip());
                if (levelKo == null) {
                    throw new IllegalStateException(
                            "minor-teams.yml: unknown level " + team.level() + " (" + team + ")");
                }
                levels.put(team.id(), levelKo);
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

    /**
     * 이동 기록(선수 화면 moves)의 마이너 팀 이름(이슈 #183). 경기 줄과 같은 이름(name)에 yml의 단계를 붙인다
     * (예: "귀넷(트리플A)", 하위 단계 재활 팀도 "주피터(싱글A)"·"FCL 브레이브스(루키)", 이슈 #191). 경기 줄은 단계(levelKo)를
     * 따로 보이지만 이동 기록은 팀 이름 한 칸뿐이라, 트리플A·더블A에 하나씩 있는 '콜럼버스'를 단계로 가른다. 이동 기록(MLB
     * transactions)에는 영어 지명이 없어서 표에 없는 팀(도미니칸 서머 리그·새로 생긴 팀·국가대표 등)은 받은 영어 팀 이름
     * 그대로다(이름을 지어내지 않는다. 보이면 yml에 더한다, yml 머리 설명).
     *
     * @param teamId   MLB 팀 번호(player_move.from_team_id·to_team_id, 모르면 null)
     * @param fullName MLB가 준 영어 팀 이름
     */
    String moveName(Long teamId, String fullName) {
        String name = name(teamId, null, fullName);
        String level = teamId == null ? null : levels.get(teamId);
        return name == null || level == null ? name : name + "(" + level + ")";
    }
}
