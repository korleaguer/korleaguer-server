package com.korleaguer.domain;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 우리 팀들(메이저에 있는 우리 선수의 지금 구단)과 그 경기가 있는지. DB만 읽는다.
 * 수집(시즌 중인지 보고 일정을 받을지 정하기)과 화면(홈이 비시즌인지 정하기)이 같은 기준을 써야 해서 core에 둔다.
 */
@Component
public class TrackedTeamGames {

    private final AffiliationRepository affiliations;
    private final GameRepository games;

    public TrackedTeamGames(AffiliationRepository affiliations, GameRepository games) {
        this.affiliations = affiliations;
        this.games = games;
    }

    /** 우리 팀들의 MLB 팀 번호. 마이너에 있는 선수의 구단은 넣지 않는다 */
    public List<Long> teamIds() {
        return affiliations.findAllCurrent().stream()
                .filter(a -> a.getPlayer().getLevel() != Level.MINORS)
                .map(a -> a.getTeam().getMlbTeamId())
                .distinct()
                .toList();
    }

    /** 우리 팀들의 경기가 now 앞뒤 days일 안에 있는지(시즌 중인지) */
    public boolean hasGamesAround(Instant now, int days) {
        List<Long> teamIds = teamIds();
        return !teamIds.isEmpty() && games.existsTeamGameBetween(teamIds,
                now.minus(Duration.ofDays(days)), now.plus(Duration.ofDays(days)));
    }

    /**
     * 지금부터 거슬러 days일 안에 시작한 우리 팀들의 경기가 있는지. 홈이 비시즌인지 정할 때 쓴다(api.OffSeasonFinder).
     * 앞으로 잡힌 경기는 보지 않는다: 개막 경기가 시작되기 전까지는 비시즌 화면(개막까지 남은 날)을 둔다.
     */
    public boolean hasRecentGames(Instant now, int days) {
        List<Long> teamIds = teamIds();
        return !teamIds.isEmpty() && games.existsTeamGameBetween(teamIds, now.minus(Duration.ofDays(days)), now);
    }
}
