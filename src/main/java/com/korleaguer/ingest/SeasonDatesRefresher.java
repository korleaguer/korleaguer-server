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
import java.util.Optional;

/**
 * MLB 시즌 날짜(정규시즌 개막일·종료일, 포스트시즌 종료일)를 받아 season_dates에 저장한다. 지금 시즌을 정할 때(CurrentSeason)와
 * 비시즌 홈의 다음 개막일(OffSeasonFinder)에 쓴다.
 * <ul>
 *   <li>지금 시즌은 올해(미국 날짜 기준 연도) 개막일만으로 정해진다. 그래서 올해 개막일이 오기 전까지는 올해 날짜를 묻는다
 *       (seasons/{year}?sportId=1). 1월 1일부터 개막일까지 하루 한 번(80여 번) 안팎이다.</li>
 *   <li>올해 정규시즌이 끝난 뒤에는 내년 날짜를 묻는다. 비시즌 홈이 '개막까지 남은 날'을 보여 주려고 쓴다.
 *       정규시즌 마지막 날 다음 날부터 12월 31일까지 하루 한 번(90여 번) 안팎이다. 시즌 중에는 묻지 않는다.</li>
 *   <li>MLB가 날짜를 바꿀 수 있어 하루(UTC 날짜)에 한 번 다시 받는다. 오늘 이미 받았으면 DB를 보고 건너뛰므로
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
        // 올해 개막 전이면 올해 날짜, 올해 정규시즌이 끝났으면 내년 날짜, 시즌 중이면 묻지 않는다
        Optional<SeasonDates> thisYear = seasonDates.findById(year);
        int target;
        if (thisYear.isEmpty() || usToday.isBefore(thisYear.get().getRegularSeasonStart())) {
            target = year;
        } else if (usToday.isAfter(thisYear.get().getRegularSeasonEnd())) {
            target = year + 1;
        } else {
            checkedOn = utcToday;
            return "";
        }
        Instant startOfDay = utcToday.atStartOfDay(ZoneOffset.UTC).toInstant();
        boolean fetchedToday = seasonDates.findById(target)
                .map(dates -> !dates.getFetchedAt().isBefore(startOfDay))
                .orElse(false);
        if (fetchedToday) {
            checkedOn = utcToday;
            return "";
        }

        MlbSeason season;
        try {
            season = client.getSeason(target);
        } catch (MlbBlockedException e) {
            throw e;
        } catch (MlbApiException e) {
            log.warn("[mlb] season {} dates failed, will retry next ingest ({})", target, e.getMessage());
            return "시즌 날짜 실패 " + target;
        }
        checkedOn = utcToday;
        if (season != null && writer.saveSeasonDates(target, season, now)) {
            if (target == year) {
                // 새 개막일을 바로 쓰도록 기억한 시즌을 버린다(내년 날짜는 지금 시즌을 바꾸지 못한다).
                currentSeason.forget();
            }
            return "시즌 날짜 " + target;
        }
        return "시즌 날짜 발표 전 " + target;
    }
}
