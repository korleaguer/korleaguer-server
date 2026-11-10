package com.korleaguer.ingest;

import com.korleaguer.client.MlbApiException;
import com.korleaguer.client.MlbBlockedException;
import com.korleaguer.client.MlbClient;
import com.korleaguer.client.MlbSeason;
import com.korleaguer.config.CurrentSeason;
import com.korleaguer.domain.SeasonDates;
import com.korleaguer.domain.SeasonDatesRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * MLB 시즌 날짜(정규시즌 개막일·종료일, 포스트시즌 종료일)를 받아 season_dates에 저장한다. 지금 시즌을 정할 때(CurrentSeason) 쓴다.
 * <ul>
 *   <li>올해와 내년(미국 날짜 기준 연도)을 해마다 1번씩 묻는다(seasons/{year}?sportId=1).</li>
 *   <li>하루(UTC 날짜)에 한 번만 받는다. 오늘 이미 받은 해는 DB를 보고 건너뛰므로 앱을 다시 띄워도 또 묻지 않는다.</li>
 *   <li>아직 발표되지 않은 해(MLB가 빈 목록을 준다)는 저장하지 않는다.</li>
 *   <li>korleaguer.season으로 시즌을 고정했으면 날짜가 필요 없으니 묻지 않는다.</li>
 *   <li>403/429면 그대로 던져 그 실행을 멈춘다. 다른 오류는 그해만 건너뛰고 다음 수집 때 다시 묻는다.</li>
 * </ul>
 */
@Component
public class SeasonDatesRefresher {

    private static final Logger log = LoggerFactory.getLogger(SeasonDatesRefresher.class);

    private final MlbClient client;
    private final SeasonDatesRepository seasonDates;
    private final IngestWriter writer;
    private final CurrentSeason currentSeason;
    private final Clock clock;
    // 두 해를 모두 물어본 날(UTC 날짜). 발표 전인 해를 같은 날 다시 묻지 않으려고 둔다. 앱이 떠 있는 동안만 기억한다.
    private volatile LocalDate checkedOn;

    public SeasonDatesRefresher(MlbClient client, SeasonDatesRepository seasonDates, IngestWriter writer,
                                CurrentSeason currentSeason, Clock clock) {
        this.client = client;
        this.seasonDates = seasonDates;
        this.writer = writer;
        this.currentSeason = currentSeason;
        this.clock = clock;
    }

    /**
     * 오늘 아직 받지 않은 해의 시즌 날짜를 받는다.
     *
     * @return 수집 기록 메시지에 붙일 한 줄. 할 일이 없었으면 빈 문자열
     * @throws MlbBlockedException 403/429
     */
    public String refresh() {
        if (currentSeason.isFixed()) {
            return "";
        }
        Instant now = clock.instant();
        LocalDate utcToday = LocalDate.ofInstant(now, ZoneOffset.UTC);
        if (utcToday.equals(checkedOn)) {
            return "";
        }
        Instant startOfDay = utcToday.atStartOfDay(ZoneOffset.UTC).toInstant();
        int thisYear = LocalDate.ofInstant(now, GameIngest.US_EASTERN).getYear();
        List<Integer> due = List.of(thisYear, thisYear + 1).stream()
                .filter(year -> !fetchedSince(year, startOfDay))
                .toList();

        List<Integer> saved = new ArrayList<>();
        List<Integer> notYet = new ArrayList<>();
        int failed = 0;
        try {
            for (int year : due) {
                MlbSeason season;
                try {
                    season = client.getSeason(year);
                } catch (MlbBlockedException e) {
                    throw e;
                } catch (MlbApiException e) {
                    log.warn("[mlb] season {} dates failed, will retry next ingest ({})", year, e.getMessage());
                    failed++;
                    continue;
                }
                if (season != null && writer.saveSeasonDates(year, season, now)) {
                    saved.add(year);
                } else {
                    notYet.add(year);
                }
            }
        } finally {
            if (!saved.isEmpty()) {
                // 새 개막일을 바로 쓰도록 기억한 시즌을 버린다.
                currentSeason.forget();
            }
        }
        if (failed == 0) {
            checkedOn = utcToday;
        }
        return describe(saved, notYet, failed);
    }

    private boolean fetchedSince(int season, Instant since) {
        return seasonDates.findById(season)
                .map(SeasonDates::getFetchedAt)
                .filter(fetchedAt -> !fetchedAt.isBefore(since))
                .isPresent();
    }

    // 예: "시즌 날짜 2026·2027", "시즌 날짜 2027, 발표 전 2028", "시즌 날짜 실패 1개"
    private static String describe(List<Integer> saved, List<Integer> notYet, int failed) {
        List<String> parts = new ArrayList<>();
        if (!saved.isEmpty()) {
            parts.add(joined(saved));
        }
        if (!notYet.isEmpty()) {
            parts.add("발표 전 " + joined(notYet));
        }
        if (failed > 0) {
            parts.add("실패 " + failed + "개");
        }
        return parts.isEmpty() ? "" : "시즌 날짜 " + String.join(", ", parts);
    }

    private static String joined(List<Integer> years) {
        return years.stream().map(String::valueOf).collect(Collectors.joining("·"));
    }
}
