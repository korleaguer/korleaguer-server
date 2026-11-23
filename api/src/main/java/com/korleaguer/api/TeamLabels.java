package com.korleaguer.api;

import com.korleaguer.domain.Team;
import com.korleaguer.support.MlbTeams;
import com.korleaguer.support.TeamNames;

/** 응답에 넣을 팀 표시값. 팀이 없으면(소속 없음, 받기 전) null */
final class TeamLabels {

    private TeamLabels() {
    }

    static String korean(Team team) {
        return team == null ? null : TeamNames.korean(team.getAbbreviation(), team.getNameEn());
    }

    static String abbreviation(Team team) {
        return team == null ? null : team.getAbbreviation();
    }

    static String english(Team team) {
        return team == null ? null : team.getNameEn();
    }

    /** MLB 팀 번호와 이름(시즌별 기록 등). MLB 30개 구단이면 한글 이름, 아니면(마이너 팀 등) 받은 이름 그대로 */
    static String mlbTeam(Long mlbTeamId, String name) {
        String abbreviation = MlbTeams.abbreviation(mlbTeamId);
        return abbreviation == null ? name : TeamNames.korean(abbreviation, name);
    }

    /**
     * 이동 기록(선수 화면 moves)의 팀 이름. MLB 30개 구단이면 한글 이름(미네소타), 아니면 마이너 팀의 한국어 지명과 단계
     * (귀넷(트리플A), 주피터(싱글A), MinorTeamNames.moveName, 이슈 #183·#191). 표에 없는 팀(도미니칸 서머 리그·국가대표
     * 등)은 받은 이름 그대로다.
     * 이동 기록에는 MLB 팀 번호가 있어(player_move) 이름이 아니라 번호로 찾는다
     */
    static String moveTeam(Long mlbTeamId, String name, MinorTeamNames minorTeams) {
        return MlbTeams.abbreviation(mlbTeamId) != null ? mlbTeam(mlbTeamId, name)
                : minorTeams.moveName(mlbTeamId, name);
    }
}
