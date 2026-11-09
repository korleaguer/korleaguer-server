package com.korleaguer.api;

import com.korleaguer.domain.GameRepository;
import com.korleaguer.domain.Team;
import com.korleaguer.support.KoreanTime;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 홈의 "최근 경기"를 고른다. 선수당 한 줄, 구간 안 가장 최근 경기다.
 * 최근 24시간에 경기가 없으면 데이터가 있는 마지막 경기일(한국 날짜)로 대신한다.
 */
@Component
class RecentGamesFinder {

    private static final Duration RECENT_WINDOW = Duration.ofHours(24);

    private final GameLineAssembler assembler;
    private final GameRepository games;

    RecentGamesFinder(GameLineAssembler assembler, GameRepository games) {
        this.assembler = assembler;
        this.games = games;
    }

    RecentGames find(List<RosterMember> roster, Instant now, boolean statsReady) {
        List<GameLine> last24h = latestPerPlayer(assembler.lines(roster, now.minus(RECENT_WINDOW), now, statsReady));
        if (!last24h.isEmpty()) {
            LocalDate latest = last24h.stream()
                    .map(GameLine::startTimeKst)
                    .max(Comparator.naturalOrder())
                    .map(OffsetDateTime::toLocalDate)
                    .orElseThrow();
            return new RecentGames(false, latest, last24h);
        }

        List<Long> teamIds = roster.stream()
                .map(RosterMember::team)
                .filter(Objects::nonNull)
                .map(Team::getMlbTeamId)
                .distinct()
                .toList();
        Optional<Instant> lastGame = teamIds.isEmpty() ? Optional.empty() : games.findLastFinalStartTime(teamIds, now);
        if (lastGame.isEmpty()) {
            return new RecentGames(true, null, List.of());
        }
        LocalDate day = KoreanTime.kstDate(lastGame.get());
        List<GameLine> lines = assembler.lines(roster, KoreanTime.startOfKstDay(day),
                KoreanTime.startOfKstDay(day.plusDays(1)), statsReady);
        return new RecentGames(true, day, latestPerPlayer(lines));
    }

    // lines는 선수 순, 같은 선수는 최신 경기부터 정렬돼 있다. 선수마다 첫 줄만 남긴다.
    private static List<GameLine> latestPerPlayer(List<GameLine> lines) {
        Map<Long, GameLine> latest = new LinkedHashMap<>();
        for (GameLine line : lines) {
            latest.putIfAbsent(line.playerId(), line);
        }
        return List.copyOf(latest.values());
    }
}
