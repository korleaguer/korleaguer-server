package com.korleaguer.ingest;

import com.korleaguer.client.MlbApiException;
import com.korleaguer.client.MlbBlockedException;
import com.korleaguer.client.MlbClient;
import com.korleaguer.client.MlbGameLogSplit;
import com.korleaguer.client.MlbStat;
import com.korleaguer.config.CurrentSeason;
import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.LeagueAverage;
import com.korleaguer.domain.LeagueAverageRepository;
import com.korleaguer.domain.MinorGameLogRepository;
import com.korleaguer.domain.MinorSeasonLevel;
import com.korleaguer.support.Woba;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 리그 단계별 한 시즌 평균(리그 wOBA·타석당 득점·평균자책점)을 받아 league_average에 저장한다(이슈 #233).
 * 화면용 API(api.LeagueEnvironments)는 이 표만 읽어 wRC+·ERA+를 '그 리그 평균 = 100'으로 잰다.
 * 마이너는 리그 환경이 다르다(2026: wOBA MLB .314, 트리플A .338, 더블A .331). 그래서 단계마다 따로 받는다.
 * <ul>
 *   <li>받는 (시즌, 단계): 화면 계산이 묻는 조합을 DB에서 뽑는다(needed).
 *       MLB는 지금 시즌과 우리 선수의 경기 기록(메이저·마이너)이 있는 시즌, 마이너 단계는 minor_game_log에 나오는 (시즌, 단계).</li>
 *   <li>저장된 줄이 없는 조합은 받는다. 지금 시즌 줄은 시즌 중일 때만 하루(UTC 날짜)에 한 번 다시 받는다.
 *       지난 시즌 줄과 비시즌의 지금 시즌 줄은 한 번 받으면 다시 받지 않는다(끝난 시즌 값은 바뀌지 않는다).</li>
 *   <li>조합 하나에 MLB 요청 2번(teams/stats 타격·투구 합계). 한 번 실행은 많아야 받을 조합 수 × 2번이다.</li>
 *   <li>값이 안 나오는 응답(타석이나 아웃이 0: 개막 전, 샘플 모드)은 저장하지 않고 그날(UTC)은 다시 묻지 않는다.
 *       그날 물었다는 것은 앱이 떠 있는 동안만 기억한다(다시 띄우면 한 번 더 묻는다).</li>
 *   <li>403/429면 그대로 던져 그 실행을 멈춘다. 다른 오류는 그 조합만 건너뛰고(있던 줄은 그대로 둔다) RETRY_AFTER 뒤에 다시 묻는다
 *       (계속 실패하는 조합을 수집 때마다 다시 묻지 않게. 앱이 떠 있는 동안만 기억한다).</li>
 * </ul>
 */
@Component
public class LeagueAverageRefresher {

    private static final Logger log = LoggerFactory.getLogger(LeagueAverageRefresher.class);

    /** 받다 실패한 조합을 다시 묻기까지 */
    static final Duration RETRY_AFTER = Duration.ofHours(1);
    /** 받는 순서: 새 시즌부터, 같은 시즌은 메이저부터 단계 순(sportId 순) */
    private static final Comparator<Key> NEWEST_SEASON_THEN_LEVEL = Comparator.comparingInt(Key::season).reversed()
            .thenComparingInt(key -> LeagueAverage.SPORT_IDS.get(key.level()));

    /** 리그 평균 한 줄의 자리 */
    record Key(int season, String level) {
    }

    private final MlbClient client;
    private final LeagueAverageRepository averages;
    private final AppearanceRepository appearances;
    private final MinorGameLogRepository minorGameLogs;
    private final CurrentSeason currentSeason;
    private final Clock clock;
    // 값이 안 나온 조합 → 물은 날(UTC 날짜). 같은 날 다시 묻지 않으려고 둔다. 앱이 떠 있는 동안만 기억한다.
    private final Map<Key, LocalDate> emptyOn = new ConcurrentHashMap<>();
    // 받다 실패한 조합 → 실패한 시각. RETRY_AFTER 동안 다시 묻지 않으려고 둔다. 앱이 떠 있는 동안만 기억한다.
    private final Map<Key, Instant> failedAt = new ConcurrentHashMap<>();

    public LeagueAverageRefresher(MlbClient client, LeagueAverageRepository averages, AppearanceRepository appearances,
                                  MinorGameLogRepository minorGameLogs, CurrentSeason currentSeason, Clock clock) {
        this.client = client;
        this.averages = averages;
        this.appearances = appearances;
        this.minorGameLogs = minorGameLogs;
        this.currentSeason = currentSeason;
        this.clock = clock;
    }

    /**
     * 받을 때가 된 (시즌, 단계)의 리그 평균을 받아 저장한다.
     *
     * @param inSeason 지금 시즌 중인지(IngestService.inSeason). 시즌 중일 때만 지금 시즌 줄을 하루 한 번 다시 받는다
     * @return 수집 기록 메시지에 붙일 한 줄. 저장한 것도 실패한 것도 없으면 빈 문자열
     * @throws MlbBlockedException 403/429
     */
    public String refresh(boolean inSeason) {
        int season = currentSeason.current();
        Instant now = clock.instant();
        LocalDate utcToday = LocalDate.ofInstant(now, ZoneOffset.UTC);
        Instant startOfDay = utcToday.atStartOfDay(ZoneOffset.UTC).toInstant();
        Map<Key, LeagueAverage> saved = new HashMap<>();
        for (LeagueAverage row : averages.findAll()) {
            saved.put(new Key(row.getSeason(), row.getLevel()), row);
        }
        int done = 0;
        int failed = 0;
        for (Key key : needed(season)) {
            LeagueAverage row = saved.get(key);
            boolean due = row == null
                    || (key.season() == season && inSeason && row.getFetchedAt().isBefore(startOfDay));
            if (!due || utcToday.equals(emptyOn.get(key))) {
                continue;
            }
            Instant lastFailure = failedAt.get(key);
            if (lastFailure != null && now.isBefore(lastFailure.plus(RETRY_AFTER))) {
                continue;
            }
            try {
                int sportId = LeagueAverage.SPORT_IDS.get(key.level());
                LeagueAverage.Values values = fromTotals(
                        client.getTeamSeasonTotals(sportId, MlbClient.Group.HITTING, key.season()),
                        client.getTeamSeasonTotals(sportId, MlbClient.Group.PITCHING, key.season()));
                if (values == null) {
                    emptyOn.put(key, utcToday);
                    log.info("[mlb] league average {} {}: no totals yet, will ask again tomorrow", key.level(),
                            key.season());
                    continue;
                }
                LeagueAverage target = row != null ? row : new LeagueAverage(key.season(), key.level());
                target.update(values, now);
                averages.save(target);
                failedAt.remove(key);
                done++;
            } catch (MlbBlockedException e) {
                throw e;
            } catch (MlbApiException e) {
                log.warn("[mlb] league average {} {} failed, will retry in {} ({})", key.level(), key.season(),
                        RETRY_AFTER, e.getMessage());
                failedAt.put(key, now);
                failed++;
            }
        }
        if (done == 0 && failed == 0) {
            return "";
        }
        return "리그 평균 " + done + "개" + (failed > 0 ? ", 실패 " + failed + "개" : "");
    }

    /**
     * 화면 계산이 묻는 (시즌, 단계). DB만 본다.
     * <ul>
     *   <li>MLB: 지금 시즌(내 선수 카드의 기간 wRC+·ERA+, 비시즌 홈 성적표)과 우리 선수의 메이저·마이너 경기 기록이 있는 시즌
     *       (성적표·이동 연표는 그 시즌의 MLB 평균을 늘 같이 묻는다).</li>
     *   <li>마이너 단계: minor_game_log에 나오는 (시즌, 단계)와 (공식 날짜의 연도, 단계). 성적표·연표는 시즌으로,
     *       내 선수 카드의 마이너 기간은 공식 날짜의 연도로 묻는다. 평균을 두지 않는 단계(약어를 모르는 경기)는 뺀다.</li>
     * </ul>
     */
    List<Key> needed(int currentSeason) {
        Set<Integer> mlbSeasons = new TreeSet<>();
        mlbSeasons.add(currentSeason);
        mlbSeasons.addAll(appearances.findSeasons());
        Set<Key> keys = new TreeSet<>(NEWEST_SEASON_THEN_LEVEL);
        for (MinorSeasonLevel minor : minorGameLogs.findSeasonLevels()) {
            mlbSeasons.add(minor.season());
            mlbSeasons.add(minor.gameYear());
            String level = minor.level();
            if (level != null && !LeagueAverage.MLB.equals(level) && LeagueAverage.SPORT_IDS.containsKey(level)) {
                keys.add(new Key(minor.season(), level));
                keys.add(new Key(minor.gameYear(), level));
            }
        }
        mlbSeasons.forEach(year -> keys.add(new Key(year, LeagueAverage.MLB)));
        return new ArrayList<>(keys);
    }

    /** 팀별 합계를 더해 리그 평균으로. 타석·아웃이 없으면 null */
    static LeagueAverage.Values fromTotals(List<MlbGameLogSplit> hitting, List<MlbGameLogSplit> pitching) {
        Woba.Sum sum = new Woba.Sum();
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
        return new LeagueAverage.Values(woba, (double) runs / plateAppearances, earnedRuns * 27.0 / outs);
    }

    private static List<MlbStat> stats(List<MlbGameLogSplit> splits) {
        return splits == null ? List.of() : splits.stream().map(MlbGameLogSplit::stat).filter(Objects::nonNull).toList();
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
