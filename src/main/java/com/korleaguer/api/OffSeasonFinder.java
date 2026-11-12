package com.korleaguer.api;

import com.korleaguer.config.CurrentSeason;
import com.korleaguer.domain.SeasonDates;
import com.korleaguer.domain.SeasonDatesRepository;
import com.korleaguer.ingest.GameIngest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * 홈이 비시즌인지 정하고 비시즌 정보(OffSeason)를 만든다. DB만 읽는다.
 * <ul>
 *   <li>최근 QUIET_DAYS일 안에 시작한 우리 선수 구단 경기가 있으면 시즌 중이다. 시즌이 끝났거나, 스프링캠프 중이거나
 *       (시범경기는 저장하지 않는다), 포스트시즌에서 모두 탈락했으면 비시즌이다. 개막 경기가 시작되면 바로 시즌 중이 된다.</li>
 *   <li>정규시즌 기간(개막일~정규시즌 마지막 날) 안이면 경기가 없어도 시즌 중으로 본다. 우리 선수가 모두 부상·마이너라
 *       잠깐 경기가 없는 것이지 시즌이 끝난 게 아니다(지금 시즌 날짜를 아직 받지 못했으면 경기만 본다).</li>
 *   <li>다음 개막일은 season_dates의 다음 시즌 정규시즌 개막일이다(SeasonDatesRefresher가 정규시즌이 끝나면 받아 둔다).</li>
 *   <li>시즌 성적표(report)는 여기서 비워 두고 홈(BaseballQueryService)이 SeasonReportFinder로 채운다.</li>
 * </ul>
 */
@Component
public class OffSeasonFinder {

    /** 이 기간 안에 시작한 우리 선수 구단 경기가 하나도 없으면 비시즌이다. 올스타 휴식(나흘)보다 길게 둔다. */
    static final int QUIET_DAYS = 7;

    private final GameIngest gameIngest;
    private final SeasonDatesRepository seasonDates;
    private final CurrentSeason currentSeason;

    public OffSeasonFinder(GameIngest gameIngest, SeasonDatesRepository seasonDates, CurrentSeason currentSeason) {
        this.gameIngest = gameIngest;
        this.seasonDates = seasonDates;
        this.currentSeason = currentSeason;
    }

    /** 비시즌이면 비시즌 정보, 시즌 중이면 null */
    OffSeason find(List<RosterMember> roster, Instant now) {
        if (gameIngest.hasRecentGames(now, QUIET_DAYS)) {
            return null;
        }
        int season = currentSeason.current();
        LocalDate usToday = gameIngest.usDate(now);
        Optional<SeasonDates> thisSeason = seasonDates.findById(season);
        if (thisSeason.isPresent() && inRegularSeason(thisSeason.get(), usToday)) {
            return null;
        }
        LocalDate opening = seasonDates.findById(season + 1)
                .map(SeasonDates::getRegularSeasonStart)
                .filter(day -> !day.isBefore(usToday))
                .orElse(null);
        Integer daysToOpening = opening == null ? null : (int) ChronoUnit.DAYS.between(usToday, opening);
        List<Long> playerIds = roster.stream().map(m -> m.player().getId()).toList();
        return new OffSeason(season, season + 1, opening, daysToOpening, playerIds, List.of());
    }

    private static boolean inRegularSeason(SeasonDates dates, LocalDate day) {
        return !day.isBefore(dates.getRegularSeasonStart()) && !day.isAfter(dates.getRegularSeasonEnd());
    }
}
