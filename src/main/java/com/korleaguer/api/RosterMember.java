package com.korleaguer.api;

import com.korleaguer.domain.Level;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.Team;

/** 선수와 그 선수의 현재 소속 팀(없거나 받기 전이면 null) */
record RosterMember(Player player, Team team) {

    /**
     * 이 선수의 경기로 칠 메이저 구단. 지금 마이너에 있으면 소속 구단이 있어도 null이다
     * (그 구단의 경기는 이 선수가 뛸 수 없는 경기라서 결장으로도, 마지막 경기일로도 보지 않는다).
     */
    Team majorTeam() {
        return player.getLevel() == Level.MINORS ? null : team;
    }
}
