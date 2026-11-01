package com.korleaguer.api;

import com.korleaguer.domain.Team;
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
}
