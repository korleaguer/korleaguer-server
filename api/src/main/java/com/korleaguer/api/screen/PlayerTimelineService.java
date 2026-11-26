package com.korleaguer.api.screen;

import com.korleaguer.api.player.MoveEntry;

import com.korleaguer.api.BadRequestException;

import com.korleaguer.api.season.SeasonReports;

import com.korleaguer.api.offseason.OffSeasonFinder;
import com.korleaguer.api.player.MinorTeamNames;
import com.korleaguer.api.season.MinorRecords;
import com.korleaguer.api.season.SeasonReport;
import com.korleaguer.api.season.SeasonReportFinder;
import com.korleaguer.api.season.SeasonReports;

import com.korleaguer.api.screen.PlayerTimelineResponse.PlayerTimeline;
import com.korleaguer.api.screen.PlayerTimelineResponse.Stat;
import com.korleaguer.api.season.SeasonReports.GameDay;
import com.korleaguer.config.CurrentSeason;
import com.korleaguer.config.ResponseCaches;
import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.Level;
import com.korleaguer.domain.MinorGameLogRepository;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerMove;
import com.korleaguer.domain.PlayerMoveRepository;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.domain.Role;
import com.korleaguer.support.BaseballMath;
import com.korleaguer.support.KoreanTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * GET /api/players/timeline(선수별 시즌 이동 연표). 등록 선수 전원의 띠 구간·이동·시즌 숫자를 한 번에 준다.
 * <ul>
 *   <li>띠는 그 시즌 3월 1일부터 오늘(한국 날짜)까지, 지난 시즌이면 다음 해 2월 말일까지(PlayerTimelines).</li>
 *   <li>이동은 선수 화면과 같은 규칙(MoveEntry.fanMoves)으로 팬에게 보일 것만 고른다.</li>
 *   <li>시즌 숫자는 홈 비시즌 성적표와 같은 계산(SeasonReportFinder → SeasonReports.build → SeasonRates)이다.
 *       그래서 시즌 중에도 나오고, 같은 선수·시즌이면 같은 값이다. 기사·'MLB 안 위치'는 읽지 않는다.</li>
 *   <li>응답은 시즌마다 캐시한다(ResponseCaches.TIMELINE_CACHE). 데이터나 날짜가 바뀔 때 비우고(ResponseCaches),
 *       CacheWarmer가 지금 시즌을 미리 채워 둔다.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class PlayerTimelineService {

    private static final Logger log = LoggerFactory.getLogger(PlayerTimelineService.class);
    /** 요청할 수 있는 시즌 범위(말도 안 되는 값을 400으로) */
    static final int MIN_SEASON = 1900;
    static final int MAX_SEASON = 2100;

    private final PlayerRepository players;
    private final PlayerMoveRepository moves;
    private final AppearanceRepository appearances;
    private final MinorGameLogRepository minorGameLogs;
    private final SeasonReportFinder seasonReports;
    private final OffSeasonFinder offSeasons;
    private final MinorTeamNames minorTeamNames;
    private final CurrentSeason currentSeason;
    private final Clock clock;

    public PlayerTimelineService(PlayerRepository players, PlayerMoveRepository moves,
                                 AppearanceRepository appearances, MinorGameLogRepository minorGameLogs,
                                 SeasonReportFinder seasonReports, OffSeasonFinder offSeasons,
                                 MinorTeamNames minorTeamNames, CurrentSeason currentSeason, Clock clock) {
        this.players = players;
        this.moves = moves;
        this.appearances = appearances;
        this.minorGameLogs = minorGameLogs;
        this.seasonReports = seasonReports;
        this.offSeasons = offSeasons;
        this.minorTeamNames = minorTeamNames;
        this.currentSeason = currentSeason;
        this.clock = clock;
    }

    /** 요청의 season. 없으면 홈과 같은 지금 시즌(CurrentSeason). 범위 밖이면 400 */
    public int resolveSeason(Integer season) {
        if (season == null) {
            return currentSeason.current();
        }
        if (season < MIN_SEASON || season > MAX_SEASON) {
            throw new BadRequestException("invalid parameter: season");
        }
        return season;
    }

    @Cacheable(cacheNames = ResponseCaches.TIMELINE_CACHE, key = "#season")
    public PlayerTimelineResponse timeline(int season) {
        Instant now = clock.instant();
        LocalDate today = KoreanTime.kstDate(now);
        LocalDate from = LocalDate.of(season, 3, 1);
        LocalDate seasonEnd = LocalDate.of(season + 1, 3, 1).minusDays(1);
        LocalDate to = today.isBefore(seasonEnd) ? today : seasonEnd;
        // 홈의 비시즌 판정과 같은 기준(OffSeasonFinder)
        boolean inSeason = season == currentSeason.current() && offSeasons.inSeason(now);

        // /api/players와 같은 순서(id 순)
        List<Player> roster = players.findAll(Sort.by("id"));
        Map<Long, List<MoveEntry>> movesByPlayer = movesOf(roster, from, to);
        SeasonReportFinder.TimelineMaterials materials = seasonReports.timelineMaterials(roster, season);

        List<PlayerTimeline> result = new ArrayList<>();
        for (Player player : roster) {
            SeasonReports.Input input = materials.inputs().get(player.getId());
            List<MoveEntry> playerMoves = movesByPlayer.getOrDefault(player.getId(), List.of());
            result.add(new PlayerTimeline(player.getId(),
                    PlayerTimelines.segments(playerMoves, from, to, firstGames(input)), playerMoves,
                    playerMoves.isEmpty() ? null : playerMoves.getLast().date(), stat(player, input, materials)));
        }
        return new PlayerTimelineResponse(season, seasons(), from, to, today, inSeason, result);
    }

    /**
     * 경기 기록(메이저·마이너)이 있는 시즌(오름차순, 지금 시즌까지). 이동 기록만 있는 해(운영 2025)는 넣지 않는다: 경기 없이
     * 첫 이동만으로 시작 상태를 정하면 콜업 전 구간이 메이저로 칠해지는 것처럼 띠가 틀린다
     */
    private List<Integer> seasons() {
        TreeSet<Integer> all = new TreeSet<>();
        all.addAll(appearances.findSeasons());
        all.addAll(minorGameLogs.findSeasons());
        int current = currentSeason.current();
        return all.stream().filter(season -> season <= current).toList();
    }

    /** 선수마다 from~to의 이동(날짜 오름차순, 팬에게 보일 것만) */
    private Map<Long, List<MoveEntry>> movesOf(List<Player> roster, LocalDate from, LocalDate to) {
        if (roster.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = roster.stream().map(Player::getId).toList();
        Map<Long, List<PlayerMove>> byPlayer = moves.findSinceOldestFirst(ids, from).stream()
                .filter(m -> !m.getMoveDate().isAfter(to))
                .collect(Collectors.groupingBy(m -> m.getPlayer().getId()));
        Map<Long, List<MoveEntry>> result = new HashMap<>();
        byPlayer.forEach((id, oldestFirst) -> {
            // fanMoves는 최신순을 받는다(같은 이동이 두 번 오면 나중 것을 남기는 쪽이 같도록 선수 화면과 같은 순서로)
            List<PlayerMove> newestFirst = new ArrayList<>(oldestFirst);
            Collections.reverse(newestFirst);
            List<MoveEntry> fan = new ArrayList<>(MoveEntry.fanMoves(newestFirst, minorTeamNames));
            Collections.reverse(fan);
            result.put(id, fan);
        });
        return result;
    }

    /** 그 시즌 첫 메이저·마이너 경기(날짜는 미국 날짜, 이동 기록 날짜와 같은 잣대) */
    private static PlayerTimelines.FirstGames firstGames(SeasonReports.Input input) {
        if (input == null) {
            return PlayerTimelines.FirstGames.NONE;
        }
        GameDay major = input.majors().stream().filter(g -> g.date() != null)
                .min(Comparator.comparing(GameDay::date)).orElse(null);
        GameDay minor = input.minors().stream().filter(g -> g.date() != null)
                .min(Comparator.comparing(GameDay::date)).orElse(null);
        return new PlayerTimelines.FirstGames(major == null ? null : major.date(), major == null ? null : major.teamKo(),
                minor == null ? null : minor.date(), minor == null ? null : MinorRecords.levelKo(minor.level()));
    }

    /** 시즌 숫자 한 줄. 기록이 없거나 계산이 실패하면 null(그 선수의 띠·이동은 그대로 나간다) */
    private Stat stat(Player player, SeasonReports.Input input, SeasonReportFinder.TimelineMaterials materials) {
        if (input == null) {
            return null;
        }
        try {
            SeasonReport report = SeasonReports.build(input, materials.environments()).report();
            return stat(input, report);
        } catch (RuntimeException e) {
            log.warn("timeline stat for {} failed, left out: {}", player.getNameKo(), e.toString());
            return null;
        }
    }

    static Stat stat(SeasonReports.Input input, SeasonReport report) {
        boolean pitcher = input.role() == Role.PITCHER;
        boolean minors = report.level() == Level.MINORS;
        List<GameDay> games = (minors ? input.minors() : input.majors()).stream().filter(g -> g.counted(pitcher)).toList();
        if (games.isEmpty() && report.wrcPlus() == null && report.eraPlus() == null) {
            return null;
        }
        String levelKo = null;
        if (minors) {
            levelKo = games.stream().collect(Collectors.groupingBy(g -> g.level() == null ? "" : g.level(),
                            Collectors.counting()))
                    .entrySet().stream().max(Map.Entry.comparingByValue())
                    .map(e -> MinorRecords.levelKo(e.getKey().isEmpty() ? null : e.getKey())).orElse(null);
        }
        if (pitcher) {
            int outs = games.stream().mapToInt(g -> SeasonReports.orZero(g.pitching().pitchingOuts())).sum();
            int earnedRuns = games.stream().mapToInt(g -> SeasonReports.orZero(g.pitching().earnedRuns())).sum();
            return new Stat(report.level(), levelKo, games.size(), null, null, report.eraPlus(),
                    BaseballMath.era(earnedRuns, outs));
        }
        int hits = games.stream().mapToInt(g -> SeasonReports.orZero(g.batting().hits())).sum();
        int atBats = games.stream().mapToInt(g -> SeasonReports.orZero(g.batting().atBats())).sum();
        return new Stat(report.level(), levelKo, games.size(), report.wrcPlus(), BaseballMath.avg(hits, atBats), null,
                null);
    }
}
