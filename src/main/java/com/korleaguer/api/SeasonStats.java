package com.korleaguer.api;

import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.Batting;
import com.korleaguer.domain.Pitching;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.Team;
import com.korleaguer.support.BaseballMath;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** 경기별 기록(Appearance)을 더해 시즌 성적을 만든다. 빈 칸(null)은 0으로 본다. */
final class SeasonStats {

    private SeasonStats() {
    }

    static HitterSeason hitter(Player player, String photoUrl, Team team, List<Appearance> appearances) {
        List<Batting> lines = appearances.stream().map(Appearance::getBatting).filter(Objects::nonNull).toList();
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
                TeamLabels.korean(team),
                TeamLabels.abbreviation(team),
                appearances.size(),
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
                BaseballMath.ops(hits, walks, hitByPitch, atBats, sacFlies, totalBases));
    }

    static PitcherSeason pitcher(Player player, String photoUrl, Team team, List<Appearance> appearances) {
        List<Pitching> lines = appearances.stream().map(Appearance::getPitching).filter(Objects::nonNull).toList();
        // 아웃 수를 모르는 경기는 평균자책점 계산(자책점·아웃 수)에서 뺀다.
        List<Pitching> withOuts = lines.stream().filter(p -> p.pitchingOuts() != null).toList();
        int outs = sum(withOuts, Pitching::pitchingOuts);

        return new PitcherSeason(
                player.getId(),
                player.getNameKo(),
                photoUrl,
                TeamLabels.korean(team),
                TeamLabels.abbreviation(team),
                appearances.size(),
                outs,
                BaseballMath.inningsText(outs),
                BaseballMath.era(sum(withOuts, Pitching::earnedRuns), outs),
                sum(lines, Pitching::wins),
                sum(lines, Pitching::losses),
                sum(lines, Pitching::saves),
                sum(lines, Pitching::pitcherStrikeouts),
                sum(lines, Pitching::walksAllowed),
                sum(lines, Pitching::hitsAllowed));
    }

    private static <T> int sum(List<T> lines, Function<T, Integer> field) {
        return lines.stream().map(field).filter(Objects::nonNull).mapToInt(Integer::intValue).sum();
    }
}
