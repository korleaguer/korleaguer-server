package com.korleaguer.ingest;

import com.korleaguer.client.MlbSeason;
import com.korleaguer.domain.SeasonDates;
import com.korleaguer.domain.SeasonDatesRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;

import static com.korleaguer.ingest.MlbValues.date;

/** 시즌 날짜(정규시즌 개막일·종료일, 포스트시즌 종료일)를 한 트랜잭션으로 덮어쓴다. */
@Component
@Transactional
public class SeasonDatesWriter {

    private static final Logger log = LoggerFactory.getLogger(SeasonDatesWriter.class);

    private final SeasonDatesRepository seasonDates;

    public SeasonDatesWriter(SeasonDatesRepository seasonDates) {
        this.seasonDates = seasonDates;
    }

    /**
     * 시즌 날짜를 새 값으로 덮어쓴다. 정규시즌 개막일·종료일을 읽지 못하면 저장하지 않는다(지난 값을 둔다).
     *
     * @return 저장했으면 true
     */
    public boolean saveSeasonDates(int season, MlbSeason source, Instant fetchedAt) {
        LocalDate start = date(source.regularSeasonStartDate());
        LocalDate end = date(source.regularSeasonEndDate());
        if (start == null || end == null) {
            log.warn("[season] {}: unreadable regular season dates '{}' ~ '{}', keep the last value", season,
                    source.regularSeasonStartDate(), source.regularSeasonEndDate());
            return false;
        }
        SeasonDates row = seasonDates.findById(season).orElseGet(() -> new SeasonDates(season));
        row.update(start, end, date(source.postSeasonEndDate()), fetchedAt);
        seasonDates.save(row);
        return true;
    }
}
