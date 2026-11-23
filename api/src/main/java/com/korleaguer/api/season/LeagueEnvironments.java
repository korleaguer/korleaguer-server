package com.korleaguer.api.season;

import com.korleaguer.domain.LeagueAverage;
import com.korleaguer.domain.LeagueAverageRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 리그 단계별 한 시즌 평균(이슈 #175). 성적표 달별 wRC+·ERA+가 '그 리그 평균 = 100'이 되게 쓴다.
 * DB(league_average)만 읽는다(이슈 #233). 값은 수집기(ingest.LeagueAverageRefresher)가 MLB 팀별 시즌 합계를 더해
 * 리그 wOBA(우리 가중치, support.Woba)·타석당 득점·평균자책점으로 저장해 둔다.
 * 마이너는 리그 환경이 다르다(2026: wOBA MLB .314, 트리플A .338, 더블A .331). 그래서 트리플A .290을 MLB 기준으로 재면
 * 실제보다 좋아 보인다.
 * 저장된 줄이 없으면(아직 못 받음) STANDARD를 쓴다. 여기서는 MLB를 부르지 않고, 다음 수집이 받는다.
 */
@Component
public class LeagueEnvironments {

    /**
     * 리그 평균 한 줄.
     *
     * @param woba      리그 wOBA(support.Woba 가중치로 계산)
     * @param runsPerPa 타석당 득점
     * @param era       평균자책점
     */
    public record Env(double woba, double runsPerPa, double era) {
    }

    /** 저장된 줄이 없을 때 쓰는 표준값(FanGraphs 근사. 웹 src/lib/wrcTrend.ts와 같다) */
    public static final Env STANDARD = new Env(0.310, 0.117, 4.20);
    /** 메이저 단계 이름(성적표 GameDay.level이 null인 경기) */
    public static final String MLB = LeagueAverage.MLB;
    /** 리그 평균을 두는 단계(약어 → MLB 공식 API sportId) */
    public static final Map<String, Integer> SPORT_IDS = LeagueAverage.SPORT_IDS;

    private final LeagueAverageRepository averages;

    public LeagueEnvironments(LeagueAverageRepository averages) {
        this.averages = averages;
    }

    /** 이 시즌 단계들(MLB·AAA 등)의 리그 평균. 모르는 단계는 넣지 않는다(쓰는 쪽이 STANDARD로 잰다) */
    public Map<String, Env> of(int season, Collection<String> levels) {
        Map<String, Env> out = new HashMap<>();
        for (String level : levels) {
            if (level != null && SPORT_IDS.containsKey(level)) {
                out.put(level, get(season, level));
            }
        }
        return out;
    }

    /** 그 시즌 그 단계의 리그 평균. 저장된 줄이 없으면 STANDARD(늘 같은 객체라 쓰는 쪽이 '못 받음'을 가릴 수 있다) */
    public Env get(int season, String level) {
        return averages.findBySeasonAndLevel(season, level)
                .map(LeagueAverage::values)
                .map(values -> new Env(values.woba(), values.runsPerPa(), values.era()))
                .orElse(STANDARD);
    }
}
