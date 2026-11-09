package com.korleaguer.client;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/** 테스트용 MLB 응답 record를 짧게 만드는 도우미 */
public final class MlbFixtures {

    private static final ZoneId US_EASTERN = ZoneId.of("America/New_York");

    private MlbFixtures() {
    }

    public static MlbTeam team(long id, String name) {
        return new MlbTeam(id, name, null, null, null);
    }

    /** 타자 한 명(지금 팀 포함) */
    public static MlbPerson hitter(long id, String fullName, MlbTeam currentTeam) {
        return new MlbPerson(id, fullName, null, null, null, null, null, null, null, null, true,
                new MlbPerson.Position("6", "Shortstop", "Infielder", "SS"), null, null, null, currentTeam);
    }

    /** 끝난 경기. gameDate는 UTC 시각, 공식 날짜는 미국 동부 날짜로 정한다. */
    public static MlbScheduleGame game(long gamePk, MlbTeam away, MlbTeam home, String gameDate, String gameType) {
        return game(gamePk, away, home, gameDate, gameType, "Final", "F", "Final", 2, 4);
    }

    public static MlbScheduleGame game(long gamePk, MlbTeam away, MlbTeam home, String gameDate, String gameType,
                                       String abstractState, String codedState, String detailedState,
                                       Integer awayScore, Integer homeScore) {
        String official = LocalDate.ofInstant(Instant.parse(gameDate), US_EASTERN).toString();
        return new MlbScheduleGame(gamePk, gameType, gameDate.substring(0, 4), gameDate, official,
                new MlbScheduleGame.Status(abstractState, codedState, detailedState),
                new MlbScheduleGame.Teams(new MlbScheduleGame.Side(away, awayScore),
                        new MlbScheduleGame.Side(home, homeScore)),
                new MlbScheduleGame.Venue("Test Park"), "N", 1);
    }

    /** 타격 gameLog 한 줄 */
    public static MlbGameLogSplit batting(long personId, MlbTeam team, MlbScheduleGame game, int atBats, int hits) {
        return new MlbGameLogSplit(game.season(), game.officialDate(), game.gameType(), null, null, team, null,
                new MlbGameLogSplit.GameRef(game.gamePk()), MlbPerson.of(personId, null, null),
                hittingStat(atBats, hits));
    }

    /** 게임 목록에 없는 경기의 타격 gameLog 한 줄 */
    public static MlbGameLogSplit batting(long personId, MlbTeam team, long gamePk, String season, int atBats, int hits) {
        return new MlbGameLogSplit(season, season + "-08-01", "R", null, null, team, null,
                new MlbGameLogSplit.GameRef(gamePk), MlbPerson.of(personId, null, null), hittingStat(atBats, hits));
    }

    public static MlbStat hittingStat(int atBats, int hits) {
        return new MlbStat(1, null, atBats, 0, hits, 0, 0, 0, 0, 0, 0, 0, 0, 0, atBats, hits,
                null, null, null, null, null, null, null);
    }

    public static MlbStat pitchingStat(int outs, int hits, int runs, int earnedRuns) {
        return new MlbStat(1, 0, null, runs, hits, null, null, 0, null, 0, 1, null, null, null, null, null,
                outs, null, earnedRuns, 15, 0, 0, 0);
    }

    /** 우리 선수 한 명이 한 편에 있는 박스스코어. batting·pitching 중 null인 쪽은 빈 기록이다. */
    public static MlbBoxscore boxscore(MlbTeam away, MlbTeam home, boolean playerHome, long personId,
                                       String battingOrder, MlbStat batting, MlbStat pitching,
                                       List<Long> pitchers, int awayRuns, int homeRuns) {
        MlbStat empty = new MlbStat(null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
        MlbBoxscore.Player line = new MlbBoxscore.Player(MlbPerson.of(personId, null, null), battingOrder,
                new MlbBoxscore.GameStatus(battingOrder != null && !battingOrder.endsWith("00")),
                new MlbBoxscore.Stats(batting == null ? empty : batting, pitching == null ? empty : pitching));
        Map<String, MlbBoxscore.Player> ours = Map.of("ID" + personId, line);
        MlbBoxscore.Side awaySide = new MlbBoxscore.Side(away, new MlbBoxscore.TeamStats(runs(awayRuns)),
                playerHome ? List.of() : pitchers, playerHome ? Map.of() : ours);
        MlbBoxscore.Side homeSide = new MlbBoxscore.Side(home, new MlbBoxscore.TeamStats(runs(homeRuns)),
                playerHome ? pitchers : List.of(), playerHome ? ours : Map.of());
        return new MlbBoxscore(new MlbBoxscore.Teams(awaySide, homeSide));
    }

    private static MlbStat runs(int runs) {
        return new MlbStat(null, null, null, runs, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }
}
