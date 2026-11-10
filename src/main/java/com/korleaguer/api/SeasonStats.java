package com.korleaguer.api;

import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.Batting;
import com.korleaguer.domain.MinorGameLog;
import com.korleaguer.domain.Pitching;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.Team;
import com.korleaguer.support.BaseballMath;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** 경기별 기록(Appearance)을 더해 시즌 성적과 연속 기록을 만든다. 빈 칸(null)은 0으로 본다. */
final class SeasonStats {

    private SeasonStats() {
    }

    /** minorGames: 같은 시즌 마이너 출전 경기 수(모르거나 쓰지 않으면 0) */
    static HitterSeason hitter(Player player, String photoUrl, Team team, List<Appearance> appearances,
                               int minorGames) {
        List<Batting> lines = appearances.stream().map(Appearance::getBatting).filter(Objects::nonNull).toList();
        return hitter(player, photoUrl, TeamLabels.korean(team), TeamLabels.abbreviation(team), lines,
                appearances.size(), minorGames, null);
    }

    /**
     * 마이너 시즌 성적(이슈 #37): 그 시즌 마이너 경기별 기록을 메이저와 같은 계산으로 더한다.
     * 팀·단계는 마지막 마이너 경기의 것이다. games가 비어 있으면 안 된다.
     */
    static HitterSeason minorHitter(Player player, String photoUrl, List<MinorGameLog> games) {
        MinorGameLog last = latest(games);
        List<Batting> lines = games.stream().map(MinorGameLog::getBatting).filter(Objects::nonNull).toList();
        return hitter(player, photoUrl, last.getTeamName(), null, lines, games.size(), 0,
                MinorRecords.levelKo(last.getLevel()));
    }

    /** 마이너 투수 시즌 성적. minorHitter와 같은 기준이다. */
    static PitcherSeason minorPitcher(Player player, String photoUrl, List<MinorGameLog> games) {
        MinorGameLog last = latest(games);
        List<Pitching> lines = games.stream().map(MinorGameLog::getPitching).filter(Objects::nonNull).toList();
        return pitcher(player, photoUrl, last.getTeamName(), null, lines, games.size(), 0,
                MinorRecords.levelKo(last.getLevel()));
    }

    private static MinorGameLog latest(List<MinorGameLog> games) {
        return games.stream()
                .max(Comparator.comparing(MinorGameLog::getGameDate).thenComparing(MinorGameLog::getGamePk))
                .orElseThrow();
    }

    private static HitterSeason hitter(Player player, String photoUrl, String teamKo, String teamAbbr,
                                       List<Batting> lines, int games, int minorGames, String levelKo) {
        int atBats = sum(lines, Batting::atBats);
        int hits = sum(lines, Batting::hits);
        int walks = sum(lines, Batting::walks);
        int hitByPitch = sum(lines, Batting::hitByPitch);
        int sacFlies = sum(lines, Batting::sacFlies);
        int totalBases = lines.stream().mapToInt(Batting::totalBasesOrComputed).sum();

        return new HitterSeason(
                player.getId(),
                player.getNameKo(),
                photoUrl,
                teamKo,
                teamAbbr,
                games,
                minorGames,
                minorGames > games,
                lines.stream().mapToInt(Batting::plateAppearancesOrComputed).sum(),
                atBats,
                hits,
                sum(lines, Batting::doubles),
                sum(lines, Batting::homeRuns),
                sum(lines, Batting::rbi),
                walks,
                sum(lines, Batting::strikeouts),
                sum(lines, Batting::stolenBases),
                BaseballMath.avg(hits, atBats),
                BaseballMath.obp(hits, walks, hitByPitch, atBats, sacFlies),
                BaseballMath.slg(totalBases, atBats),
                BaseballMath.ops(hits, walks, hitByPitch, atBats, sacFlies, totalBases),
                levelKo);
    }

    /** minorGames: 같은 시즌 마이너 등판 경기 수(모르거나 쓰지 않으면 0) */
    static PitcherSeason pitcher(Player player, String photoUrl, Team team, List<Appearance> appearances,
                                 int minorGames) {
        List<Pitching> lines = appearances.stream().map(Appearance::getPitching).filter(Objects::nonNull).toList();
        return pitcher(player, photoUrl, TeamLabels.korean(team), TeamLabels.abbreviation(team), lines,
                appearances.size(), minorGames, null);
    }

    private static PitcherSeason pitcher(Player player, String photoUrl, String teamKo, String teamAbbr,
                                         List<Pitching> lines, int games, int minorGames, String levelKo) {
        List<Pitching> withOuts = withKnownOuts(lines);
        int outs = sum(withOuts, Pitching::pitchingOuts);

        return new PitcherSeason(
                player.getId(),
                player.getNameKo(),
                photoUrl,
                teamKo,
                teamAbbr,
                games,
                minorGames,
                minorGames > games,
                outs,
                BaseballMath.inningsText(outs),
                BaseballMath.era(sum(withOuts, Pitching::earnedRuns), outs),
                sum(lines, Pitching::wins),
                sum(lines, Pitching::losses),
                sum(lines, Pitching::saves),
                sum(lines, Pitching::pitcherStrikeouts),
                sum(lines, Pitching::walksAllowed),
                sum(lines, Pitching::hitsAllowed),
                levelKo);
    }

    /** 평균자책점에 넣는 자책점. 아웃 수를 모르는 경기는 뺀다(pitcher()의 era와 같은 기준). */
    static int earnedRuns(List<Appearance> appearances) {
        List<Pitching> lines = appearances.stream().map(Appearance::getPitching).filter(Objects::nonNull).toList();
        return sum(withKnownOuts(lines), Pitching::earnedRuns);
    }

    /**
     * 최근 경기부터 연속 안타 경기 수.
     * 타수도 안타도 없는 경기(볼넷만, 대주자 등)와 타격 기록이 없는 경기는 건너뛰고, 타수가 있는데 무안타면 멈춘다.
     */
    static int hitStreak(List<Appearance> appearances) {
        int streak = 0;
        for (Appearance appearance : newestFirst(appearances)) {
            Batting b = appearance.getBatting();
            int hits = b == null ? 0 : orZero(b.hits());
            int atBats = b == null ? 0 : orZero(b.atBats());
            if (hits > 0) {
                streak++;
            } else if (atBats > 0) {
                break;
            }
        }
        return streak;
    }

    /** 최근 등판부터 연속 무실점 경기 수. 투구 기록이 없는 출전(대주자 등)은 건너뛰고, 실점했거나 실점을 모르면 멈춘다. */
    static int scorelessStreak(List<Appearance> appearances) {
        int streak = 0;
        for (Appearance appearance : newestFirst(appearances)) {
            Pitching p = appearance.getPitching();
            if (p == null) {
                continue;
            }
            if (p.runsAllowed() == null || p.runsAllowed() > 0) {
                break;
            }
            streak++;
        }
        return streak;
    }

    // 아웃 수를 모르는 경기는 평균자책점 계산(자책점·아웃 수)에서 뺀다.
    private static List<Pitching> withKnownOuts(List<Pitching> lines) {
        return lines.stream().filter(p -> p.pitchingOuts() != null).toList();
    }

    private static List<Appearance> newestFirst(List<Appearance> appearances) {
        return appearances.stream()
                .sorted(Comparator.comparing((Appearance a) -> a.getGame().getStartTimeUtc()).reversed())
                .toList();
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static <T> int sum(List<T> lines, Function<T, Integer> field) {
        return lines.stream().map(field).filter(Objects::nonNull).mapToInt(Integer::intValue).sum();
    }
}
