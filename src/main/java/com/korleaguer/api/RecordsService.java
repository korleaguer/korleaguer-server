package com.korleaguer.api;

import com.korleaguer.config.CurrentSeason;
import com.korleaguer.config.DataMode;
import com.korleaguer.config.KoreanMlbProperties;
import com.korleaguer.config.ResponseCaches;
import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.Batting;
import com.korleaguer.domain.KoreanMlbSeason;
import com.korleaguer.domain.KoreanMlbSeasonRepository;
import com.korleaguer.domain.Pitching;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.domain.PlayerSeasonMetric;
import com.korleaguer.domain.SeasonType;
import com.korleaguer.support.KoreanTime;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /api/records(한국인 메이저리거 역대 기록, 이슈 #177). 사람 명단은 지금 명단(Player) + korean-mlb.yml,
 * 통산·한 시즌 값은 저장한 MLB 시즌별 기록(korean_mlb_season), 이번 시즌 '넘은 선배'는 우리 메이저 정규시즌 경기 기록으로 센다.
 * 응답은 하나뿐이라 통째로 캐시하고, 데이터가 바뀌면 다른 응답과 함께 비운다(ResponseCaches.ALL).
 */
@Service
public class RecordsService {

    private static final List<String> REGULAR = List.of(SeasonType.REGULAR.stored());

    private final PlayerRepository players;
    private final KoreanMlbSeasonRepository seasons;
    private final AppearanceRepository appearances;
    private final KoreanMlbProperties past;
    private final RosterProfiles profiles;
    private final CurrentSeason currentSeason;
    private final DataMode dataMode;
    private final Clock clock;

    public RecordsService(PlayerRepository players, KoreanMlbSeasonRepository seasons, AppearanceRepository appearances,
                          KoreanMlbProperties past, RosterProfiles profiles, CurrentSeason currentSeason,
                          DataMode dataMode, Clock clock) {
        this.players = players;
        this.seasons = seasons;
        this.appearances = appearances;
        this.past = past;
        this.profiles = profiles;
        this.currentSeason = currentSeason;
        this.dataMode = dataMode;
        this.clock = clock;
    }

    @Cacheable(cacheNames = ResponseCaches.RECORDS_CACHE, key = "'all'")
    @Transactional(readOnly = true)
    public RecordsResponse records() {
        Instant now = clock.instant();
        int season = currentSeason.current();

        // 사람: 명단 선수(MLB 번호 있는 선수) 먼저, 그다음 명단 밖 선수. 번호가 겹치면 명단 쪽만
        Map<Long, KoreanRecords.Person> people = new LinkedHashMap<>();
        Map<Long, Long> playerIdByMlbId = new HashMap<>();
        for (Player player : players.findByMlbIdIsNotNull()) {
            long mlbId = player.getMlbId();
            playerIdByMlbId.put(mlbId, player.getId());
            people.put(mlbId, new KoreanRecords.Person(mlbId, player.getId(), player.getNameKo(),
                    profiles.photoUrl(player), PlayerSeasonMetric.Group.of(player.role()),
                    profiles.returnedKo(player) == null));
        }
        for (KoreanMlbProperties.Entry entry : past.past()) {
            if (entry.mlbId() != null) {
                people.putIfAbsent(entry.mlbId(), new KoreanRecords.Person(entry.mlbId(), null, entry.nameKo(), null,
                        entry.pitcher() ? PlayerSeasonMetric.Group.PITCHING : PlayerSeasonMetric.Group.HITTING,
                        false));
            }
        }

        // 시즌 줄: 그 사람의 묶음(타자·투수) 것만
        Map<Long, List<KoreanRecords.SeasonLine>> lines = new HashMap<>();
        for (KoreanMlbSeason row : seasons.findAll()) {
            KoreanRecords.Person person = people.get(row.getMlbId());
            if (person != null && person.group() == row.getStatGroup()) {
                lines.computeIfAbsent(row.getMlbId(), id -> new ArrayList<>())
                        .add(new KoreanRecords.SeasonLine(row.getSeason(), row.counts()));
            }
        }

        // 이번 시즌 경기: 현역 명단 선수의 메이저 정규시즌 경기(오래된 것부터)
        List<Long> activeIds = people.values().stream()
                .filter(p -> p.active() && p.playerId() != null)
                .map(KoreanRecords.Person::playerId)
                .toList();
        Map<Long, List<KoreanRecords.GameLine>> games = new HashMap<>();
        if (!activeIds.isEmpty()) {
            appearances.findSeason(activeIds, season, REGULAR).stream()
                    .sorted(Comparator.comparing((Appearance a) -> a.getGame().getStartTimeUtc()))
                    .forEach(a -> {
                        Long mlbId = a.getPlayer().getMlbId();
                        KoreanRecords.Person person = mlbId == null ? null : people.get(mlbId);
                        if (person != null) {
                            games.computeIfAbsent(mlbId, id -> new ArrayList<>()).add(new KoreanRecords.GameLine(
                                    KoreanTime.kstDate(a.getGame().getStartTimeUtc()), counts(a, person.group())));
                        }
                    });
        }
        return KoreanRecords.build(dataMode.value(), season, now.truncatedTo(ChronoUnit.SECONDS),
                List.copyOf(people.values()), lines, games);
    }

    /** 경기 한 줄의 개수 기록. 그 묶음 기록이 없으면(대수비 등 기록 없는 출전) 모두 0이고 경기도 세지 않는다 */
    private static KoreanMlbSeason.Counts counts(Appearance a, PlayerSeasonMetric.Group group) {
        if (group == PlayerSeasonMetric.Group.PITCHING) {
            Pitching p = a.getPitching();
            return p == null ? new KoreanMlbSeason.Counts(0, null, null, null, null, 0, 0, 0)
                    : new KoreanMlbSeason.Counts(1, null, null, null, null, p.wins(), p.saves(), p.pitcherStrikeouts());
        }
        Batting b = a.getBatting();
        return b == null ? new KoreanMlbSeason.Counts(0, 0, 0, 0, 0, null, null, null)
                : new KoreanMlbSeason.Counts(1, b.hits(), b.homeRuns(), b.rbi(), b.stolenBases(), null, null, null);
    }
}
