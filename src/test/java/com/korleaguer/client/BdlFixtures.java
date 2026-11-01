package com.korleaguer.client;

import java.util.List;

/** 테스트용 balldontlie 응답 record를 짧게 만드는 도우미 */
public final class BdlFixtures {

    private BdlFixtures() {
    }

    public static BdlTeam team(long id, String abbreviation) {
        return new BdlTeam(id, abbreviation, abbreviation + " Club", abbreviation, abbreviation, abbreviation,
                "National", "East");
    }

    public static BdlPlayer player(long id, String fullName, String lastName, String birthPlace, BdlTeam team) {
        String firstName = fullName.substring(0, fullName.lastIndexOf(' '));
        return new BdlPlayer(id, firstName, lastName, fullName, "Shortstop", true, birthPlace,
                null, null, null, null, null, null, null, team);
    }

    public static BdlGame game(long id, BdlTeam away, BdlTeam home, String date, String seasonType) {
        return new BdlGame(id, 2026, seasonType, "postseason".equals(seasonType), date, "Test Park", home, away,
                new BdlGame.TeamData(8, 4, 0, List.of()), new BdlGame.TeamData(6, 2, 0, List.of()),
                "STATUS_FINAL", "final");
    }

    public static BdlStats batting(BdlPlayer player, BdlTeam team, long gameId, int atBats, int hits) {
        return new BdlStats(player, team, gameId,
                atBats, 0, hits, 0, 0, 0, 0, 0, 0, 0, 0, atBats, hits, 0,
                null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
