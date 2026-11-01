package com.korleaguer.api;

import com.korleaguer.domain.Player;
import com.korleaguer.domain.Team;

/** 선수와 그 선수의 현재 소속 팀(매칭 전이면 null) */
record RosterMember(Player player, Team team) {
}
