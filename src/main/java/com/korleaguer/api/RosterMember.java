package com.korleaguer.api;

import com.korleaguer.domain.Affiliation;
import com.korleaguer.domain.Level;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.Team;

/**
 * 선수와 그 선수의 소속 팀(없거나 받기 전이면 null).
 * 소속이 끝났으면(FA 등) team은 마지막 소속 팀이고 teamEnded가 true다.
 */
record RosterMember(Player player, Team team, boolean teamEnded) {

    /** 선수의 소속 줄(없으면 null)로 만든다. */
    static RosterMember of(Player player, Affiliation affiliation) {
        return affiliation == null ? new RosterMember(player, null, false)
                : new RosterMember(player, affiliation.getTeam(), affiliation.isEnded());
    }

    /**
     * 이 선수의 경기로 칠 메이저 구단. 지금 마이너에 있거나 소속이 끝났으면(FA 등) 소속 구단이 있어도 null이다
     * (그 구단의 경기는 이 선수가 뛸 수 없는 경기라서 결장으로도, 마지막 경기일로도 보지 않는다).
     */
    Team majorTeam() {
        return teamEnded || player.getLevel() == Level.MINORS ? null : team;
    }
}
