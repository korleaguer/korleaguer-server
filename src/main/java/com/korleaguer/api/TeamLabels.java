package com.korleaguer.api;

import com.korleaguer.domain.Team;
import com.korleaguer.support.MlbTeams;
import com.korleaguer.support.TeamNames;

/** 응답에 넣을 팀 표시값. 팀이 없으면(매칭 전) null */
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

    /** MLB 팀 번호와 이름(이동 기록). MLB 30개 구단이면 한글 이름, 아니면(마이너 팀 등) 받은 이름 그대로 */
    static String mlbTeam(Long mlbTeamId, String name) {
        String abbreviation = MlbTeams.abbreviation(mlbTeamId);
        return abbreviation == null ? name : TeamNames.korean(abbreviation, name);
    }
}
