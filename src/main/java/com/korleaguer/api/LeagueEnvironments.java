package com.korleaguer.api;

import com.korleaguer.client.MlbClient;
import com.korleaguer.client.MlbGameLogSplit;
import com.korleaguer.client.MlbStat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 리그 단계별 한 시즌 평균(이슈 #175). 성적표 달별 wRC+·ERA+가 '그 리그 평균 = 100'이 되게 쓴다.
 * MLB 공식 API의 팀별 시즌 합계(teams/stats)를 더해 리그 wOBA(우리 가중치, WrcMath)·타석당 득점·평균자책점을 낸다.
 * 마이너는 리그 환경이 다르다(2026: wOBA MLB .314, 트리플A .338, 더블A .331). 그래서 트리플A .290을 MLB 기준으로 재면
 * 실제보다 좋아 보인다.
 * 받은 값은 KEEP 동안 메모리에 둔다. 못 받으면 앞서 받은 값을, 그것도 없으면 STANDARD를 쓰고 RETRY 뒤 다시 묻는다.
 */
@Component
public class LeagueEnvironments {

    private static final Logger log = LoggerFactory.getLogger(LeagueEnvironments.class);

    /**
     * 리그 평균 한 줄.
     *
     * @param woba      리그 wOBA(WrcMath 가중치로 계산)
     * @param runsPerPa 타석당 득점
     * @param era       평균자책점
     */
    public record Env(double woba, double runsPerPa, double era) {
    }

    /** 못 받았을 때 쓰는 표준값(FanGraphs 근사. 웹 src/lib/wrcTrend.ts와 같다) */
    static final Env STANDARD = new Env(0.310, 0.117, 4.20);
    /** 메이저 단계 이름(성적표 GameDay.level이 null인 경기) */
    static final String MLB = "MLB";
    /** 단계 약어 → MLB 공식 API sportId */
    static final Map<String, Integer> SPORT_IDS = Map.of(MLB, 1, "AAA", 11, "AA", 12, "A+", 13, "A", 14, "ROK", 16);
    static final Duration KEEP = Duration.ofHours(12);
    static final Duration RETRY = Duration.ofMinutes(10);

    private record Cached(Env env, Instant until) {
    }

    private final MlbClient mlb;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public LeagueEnvironments(MlbClient mlb, Clock clock) {
        this.mlb = mlb;
        this.clock = clock;
    }

    /** 이 시즌 단계들(MLB·AAA 등)의 리그 평균. 모르는 단계는 넣지 않는다(쓰는 쪽이 STANDARD로 잰다) */
    Map<String, Env> of(int season, Collection<String> levels) {
        Map<String, Env> out = new HashMap<>();
        for (String level : levels) {
            if (level != null && SPORT_IDS.containsKey(level)) {
                out.put(level, get(season, level));
            }
        }
        return out;
    }

    Env get(int season, String level) {
        String key = season + "|" + level;
        Instant now = clock.instant();
        Cached cached = cache.get(key);
        if (cached != null && now.isBefore(cached.until())) {
            return cached.env();
        }
        Env fetched = null;
        try {
            int sportId = SPORT_IDS.get(level);
            fetched = fromTotals(mlb.getTeamSeasonTotals(sportId, MlbClient.Group.HITTING, season),
                    mlb.getTeamSeasonTotals(sportId, MlbClient.Group.PITCHING, season));
        } catch (RuntimeException e) {
            log.warn("league totals {} {} failed, using {}: {}", level, season,
                    cached != null ? "earlier values" : "standard values", e.toString());
        }
        Env env = fetched != null ? fetched : cached != null ? cached.env() : STANDARD;
        cache.put(key, new Cached(env, now.plus(fetched != null ? KEEP : RETRY)));
        return env;
    }

    /** 팀별 합계를 더해 리그 평균으로. 타석·아웃이 없으면 null */
    static Env fromTotals(List<MlbGameLogSplit> hitting, List<MlbGameLogSplit> pitching) {
        WrcMath.Sum sum = new WrcMath.Sum();
        int runs = 0;
        int plateAppearances = 0;
        for (MlbStat s : stats(hitting)) {
            sum.add(orZero(s.atBats()), orZero(s.hits()), orZero(s.doubles()), orZero(s.triples()),
                    orZero(s.homeRuns()), orZero(s.baseOnBalls()), orZero(s.hitByPitch()), orZero(s.sacFlies()));
            runs += orZero(s.runs());
            plateAppearances += orZero(s.plateAppearances());
        }
        int outs = 0;
        int earnedRuns = 0;
        for (MlbStat s : stats(pitching)) {
            outs += orZero(s.outs());
            earnedRuns += orZero(s.earnedRuns());
        }
        Double woba = sum.woba();
        if (woba == null || plateAppearances == 0 || outs == 0) {
            return null;
        }
        return new Env(woba, (double) runs / plateAppearances, earnedRuns * 27.0 / outs);
    }

    private static List<MlbStat> stats(List<MlbGameLogSplit> splits) {
        return splits == null ? List.of() : splits.stream().map(MlbGameLogSplit::stat).filter(Objects::nonNull).toList();
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
