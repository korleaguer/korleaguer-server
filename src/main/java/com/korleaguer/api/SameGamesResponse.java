package com.korleaguer.api;

import java.util.List;

/**
 * GET /api/players/{id}/same-games(이슈 #217). 그 시즌 메이저 경기(정규시즌·포스트시즌) 중 이 선수와 다른 우리 선수가
 * 모두 기록을 남긴 경기를 상대 선수별로 묶은 것이다. 같이 나온 경기가 없으면 pairs가 빈 목록이다(투수처럼 경기가 적은 선수).
 * pairs는 같이 나온 경기가 많은 순, 같으면 가장 최근에 만난 순이다.
 */
public record SameGamesResponse(int season, long playerId, List<Pair> pairs) {

    /**
     * 상대 선수 한 명과 같이 나온 경기.
     * wins·losses는 이 선수(playerId) 팀이 그 경기들에서 거둔 승·패다(끝난 경기만 센다. 진행 중·무승부는 둘 다 아니다).
     * 맞상대든 같은 팀 동료든 '이 선수 팀 전적'이라 뜻이 같다. teamKo는 그 경기들에서 이 선수 팀 이름이고,
     * 시즌 중 팀을 옮겨 둘 이상이면 null이다.
     * meetings는 경기마다 두 사람의 한 줄(오래된 경기부터, 더블헤더는 경기마다 한 줄)이라 games == meetings.size()다.
     */
    public record Pair(Other other, int games, int wins, int losses, String teamKo, List<Meeting> meetings) {
    }

    /** 상대 선수. photoTeamAbbr은 사진 속 유니폼 팀(웹 사진 원 바탕색), teamAbbr은 지금(마지막) 소속 팀이다. 없으면 null */
    public record Other(long playerId, String nameKo, String photoUrl, String photoTeamAbbr, String teamAbbr) {
    }

    /** 같은 경기 하나. mine은 이 선수의 줄, other는 상대 선수의 줄(둘의 gameId가 같다) */
    public record Meeting(GameLine mine, GameLine other) {
    }
}
