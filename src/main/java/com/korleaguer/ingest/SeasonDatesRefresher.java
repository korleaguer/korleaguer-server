package com.korleaguer.ingest;

import com.korleaguer.client.MlbApiException;
import com.korleaguer.client.MlbBlockedException;
import com.korleaguer.client.MlbClient;
import com.korleaguer.client.MlbSeason;
import com.korleaguer.config.CurrentSeason;
import com.korleaguer.domain.SeasonDatesRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * MLB 시즌 날짜(정규시즌 개막일·종료일, 포스트시즌 종료일)를 받아 season_dates에 저장한다. 지금 시즌을 정할 때(CurrentSeason) 쓴다.
 * <ul>
 *   <li>지금 시즌은 올해(미국 날짜 기준 연도) 개막일만으로 정해진다. 그래서 올해 날짜만, 올해 개막일이 오기 전까지만 묻는다
 *       (seasons/{year}?sportId=1). 내년 날짜나 개막한 뒤의 날짜는 결정을 바꾸지 못하므로 묻지 않는다.
 *       1년에 1월 1일부터 개막일까지 하루 한 번(80여 번) 안팎이다.</li>
 *   <li>개막 전에는 MLB가 날짜를 바꿀 수 있어 하루(UTC 날짜)에 한 번 다시 받는다. 오늘 이미 받았으면 DB를 보고 건너뛰므로
 *       앱을 다시 띄워도 또 묻지 않는다.</li>
 *   <li>아직 발표되지 않은 해(MLB가 빈 목록을 준다)는 저장하지 않고 그날은 다시 묻지 않는다.</li>
 *   <li>korleaguer.season이나 샘플 모드로 시즌을 고정했으면 날짜가 필요 없으니 묻지 않는다.</li>
 *   <li>403/429면 그대로 던져 그 실행을 멈춘다. 다른 오류는 다음 수집 때 다시 묻는다.</li>
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
    // 더 물을 필요가 없다고 정한 날(UTC 날짜). 발표 전인 해를 같은 날 다시 묻지 않으려고 둔다. 앱이 떠 있는 동안만 기억한다.
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
     * 올해 개막일이 오기 전이고 오늘 아직 받지 않았으면 올해 시즌 날짜를 받는다.
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
        LocalDate usToday = LocalDate.ofInstant(now, GameIngest.US_EASTERN);
        int year = usToday.getYear();
        Instant startOfDay = utcToday.atStartOfDay(ZoneOffset.UTC).toInstant();
        boolean settled = seasonDates.findById(year)
                .map(dates -> !usToday.isBefore(dates.getRegularSeasonStart())
                        || !dates.getFetchedAt().isBefore(startOfDay))
                .orElse(false);
        if (settled) {
            checkedOn = utcToday;
            return "";
        }

        MlbSeason season;
        try {
            season = client.getSeason(year);
        } catch (MlbBlockedException e) {
            throw e;
        } catch (MlbApiException e) {
            log.warn("[mlb] season {} dates failed, will retry next ingest ({})", year, e.getMessage());
            return "시즌 날짜 실패 " + year;
        }
        checkedOn = utcToday;
        if (season != null && writer.saveSeasonDates(year, season, now)) {
            // 새 개막일을 바로 쓰도록 기억한 시즌을 버린다.
            currentSeason.forget();
            return "시즌 날짜 " + year;
        }
        return "시즌 날짜 발표 전 " + year;
    }
}
