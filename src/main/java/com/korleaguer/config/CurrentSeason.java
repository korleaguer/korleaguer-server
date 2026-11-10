package com.korleaguer.config;

import com.korleaguer.client.SampleMlbClient;
import com.korleaguer.domain.SeasonDates;
import com.korleaguer.domain.SeasonDatesRepository;
import com.korleaguer.ingest.GameIngest;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.MonthDay;

/**
 * 지금 시즌(연도). 시즌을 쓰는 곳(수집·홈·선수·내 선수·경기 줄)은 시작할 때 한 번 읽어 두지 않고 쓸 때마다 여기서 묻는다.
 * 그래서 개막일이 지나면 앱을 다시 띄우지 않아도 새 시즌으로 넘어간다.
 * <ul>
 *   <li>korleaguer.season에 값이 있으면 그 값(테스트·수동 고정용)</li>
 *   <li>샘플 모드면 샘플 데이터의 시즌(SampleMlbClient.SEASON). 샘플에는 그 시즌 경기만 있어서, 날짜가 지나도 화면이 비지 않게 한다</li>
 *   <li>없으면 미국 날짜(동부 시간) 오늘이 올해 정규시즌 개막일 이후(그날 포함)면 올해, 전이면 작년.
 *       개막일은 수집기가 MLB에서 받아 둔 season_dates를 본다(DB만 읽는다).</li>
 *   <li>올해 개막일을 아직 받지 못했으면 3월 20일을 개막일로 보고 정한다(MLB 개막은 보통 3월 말).</li>
 * </ul>
 * 요청마다 DB를 읽지 않도록 정한 값을 몇 분(CACHE_TTL) 기억한다. 미국 날짜가 바뀌거나 새 날짜를 받으면(forget) 다시 정한다.
 */
@Component
public class CurrentSeason {

    /** 올해 개막일을 아직 모를 때 개막일로 보는 날(미국 날짜) */
    static final MonthDay FALLBACK_OPENING_DAY = MonthDay.of(3, 20);
    /** 정한 시즌을 기억하는 시간 */
    static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final Integer fixed;
    private final SeasonDatesRepository seasonDates;
    private final Clock clock;
    // 마지막으로 정한 값. 미국 날짜가 같고 만료 전이면 그대로 쓴다.
    private volatile Decided decided;

    public CurrentSeason(KorleaguerProperties properties, DataMode dataMode, SeasonDatesRepository seasonDates,
                         Clock clock) {
        this.fixed = properties.season() != null ? properties.season()
                : dataMode == DataMode.SAMPLE ? Integer.valueOf(SampleMlbClient.SEASON) : null;
        this.seasonDates = seasonDates;
        this.clock = clock;
    }

    /** 지금 시즌 연도 */
    public int current() {
        if (fixed != null) {
            return fixed;
        }
        Instant now = clock.instant();
        LocalDate usToday = LocalDate.ofInstant(now, GameIngest.US_EASTERN);
        Decided last = decided;
        if (last != null && last.usToday().equals(usToday) && now.isBefore(last.expiresAt())) {
            return last.season();
        }
        int season = decide(usToday, openingDay(usToday.getYear()));
        decided = new Decided(usToday, season, now.plus(CACHE_TTL));
        return season;
    }

    /**
     * 그 시각(미국 날짜)이 속한 시즌. 화면 기간(최근 N일, 마지막으로 뛴 기간)이 어느 시즌 경기인지 정할 때 쓴다.
     * 개막 직후 지난 시즌 끝으로 당긴 기간은 지난 시즌이다. 오늘이나 아직 오지 않은 시각이면 current()와 같다
     * ('최근 N일' 기간 끝은 한국 날짜로 내일 0시라, 미국 날짜로 개막 전날 저녁에 새 시즌으로 앞서 가지 않게 한다).
     */
    public int seasonAt(Instant instant) {
        if (fixed != null) {
            return fixed;
        }
        Instant now = clock.instant();
        LocalDate usDate = LocalDate.ofInstant(instant, GameIngest.US_EASTERN);
        if (!instant.isBefore(now) || usDate.equals(LocalDate.ofInstant(now, GameIngest.US_EASTERN))) {
            return current();
        }
        return decide(usDate, openingDay(usDate.getYear()));
    }

    /** 그해 정규시즌 개막일(받아 둔 것). 아직 못 받았으면 null */
    private LocalDate openingDay(int year) {
        return seasonDates.findById(year).map(SeasonDates::getRegularSeasonStart).orElse(null);
    }

    /** 시즌을 고정했는지(korleaguer.season 또는 샘플 모드). 고정했으면 시즌 날짜를 받을 필요가 없다. */
    public boolean isFixed() {
        return fixed != null;
    }

    /** 기억한 값을 버린다. 수집기가 시즌 날짜를 새로 받은 뒤 부른다. */
    public void forget() {
        decided = null;
    }

    /**
     * 미국 날짜 usToday의 시즌. 올해 개막일(openingDay, 모르면 null → 3월 20일) 전이면 작년, 그날부터 올해다.
     */
    static int decide(LocalDate usToday, LocalDate openingDay) {
        LocalDate opening = openingDay != null ? openingDay : FALLBACK_OPENING_DAY.atYear(usToday.getYear());
        return usToday.isBefore(opening) ? usToday.getYear() - 1 : usToday.getYear();
    }

    private record Decided(LocalDate usToday, int season, Instant expiresAt) {
    }
}
