package com.korleaguer.api;

import com.korleaguer.config.CurrentSeason;
import com.korleaguer.config.ResponseCaches;
import com.korleaguer.domain.AffiliationRepository;
import com.korleaguer.domain.Appearance;
import com.korleaguer.domain.AppearanceRepository;
import com.korleaguer.domain.Player;
import com.korleaguer.domain.PlayerLeaguePositionRepository;
import com.korleaguer.domain.PlayerRepository;
import com.korleaguer.domain.PlayerSeasonMetric;
import com.korleaguer.domain.Role;
import com.korleaguer.domain.SeasonType;
import com.korleaguer.support.KoreanTime;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 두 선수 비교에 쓸 타자 시즌 성적 묶음(GET /api/players/{id}/compare, 이슈 #221). DB만 읽는다.
 * 시즌 성적은 홈 시즌 기록과 같은 계산(SeasonStats.hitter, 메이저 정규시즌), wRC+는 선수 페이지와 같은 저장 값(PlayerSaber)이다.
 * 응답은 선수마다 캐시한다(ResponseCaches.COMPARE_CACHE, 데이터가 바뀌면 ResponseCaches가 비운다).
 */
@Service
@Transactional(readOnly = true)
public class CompareService {

    private static final List<String> REGULAR_SEASON = List.of(SeasonType.REGULAR.stored());

    private final PlayerRepository players;
    private final AffiliationRepository affiliations;
    private final AppearanceRepository appearances;
    private final PlayerLeaguePositionRepository leaguePositions;
    private final RosterProfiles profiles;
    private final CurrentSeason currentSeason;
    private final Clock clock;

    public CompareService(PlayerRepository players, AffiliationRepository affiliations,
                          AppearanceRepository appearances, PlayerLeaguePositionRepository leaguePositions,
                          RosterProfiles profiles, CurrentSeason currentSeason, Clock clock) {
        this.players = players;
        this.affiliations = affiliations;
        this.appearances = appearances;
        this.leaguePositions = leaguePositions;
        this.profiles = profiles;
        this.currentSeason = currentSeason;
        this.clock = clock;
    }

    @Cacheable(cacheNames = ResponseCaches.COMPARE_CACHE, key = "#playerId")
    public CompareResponse find(long playerId) {
        if (!players.existsById(playerId)) {
            throw new PlayerNotFoundException(playerId);
        }
        int season = currentSeason.current();
        LocalDate today = KoreanTime.kstDate(clock.instant());
        List<RosterMember> hitters = affiliations.findAllLatest().stream()
                .map(a -> RosterMember.of(a.getPlayer(), a))
                .filter(m -> m.player().role() == Role.HITTER)
                .toList();
        Map<Long, List<Appearance>> byPlayer = appearances
                .findSeason(hitters.stream().map(m -> m.player().getId()).toList(), season, REGULAR_SEASON).stream()
                .collect(Collectors.groupingBy(a -> a.getPlayer().getId()));

        // 메이저 타석이 하나라도 있는 타자만 견준다(마이너에서만 뛴 선수는 리그가 달라 뺀다)
        List<CompareResponse.Line> lines = hitters.stream()
                .map(m -> line(m, byPlayer.getOrDefault(m.player().getId(), List.of()), season, today))
                .filter(line -> line.plateAppearances() > 0)
                .toList();
        CompareResponse.Line me = lines.stream().filter(l -> l.player().id() == playerId).findFirst().orElse(null);
        if (me == null) {
            return new CompareResponse(season, playerId, null, List.of());
        }
        List<CompareResponse.Line> others = lines.stream()
                .filter(l -> l != me)
                .sorted(Comparator.comparingInt(CompareResponse.Line::plateAppearances).reversed())
                .toList();
        return new CompareResponse(season, playerId, me, others);
    }

    private CompareResponse.Line line(RosterMember member, List<Appearance> regular, int season, LocalDate today) {
        Player player = member.player();
        String photoTeam = profiles.photoTeamAbbr(player, member.team());
        HitterSeason total = SeasonStats.hitter(player, null, photoTeam, member.team(), regular, 0);
        PlayerSaber saber = PlayerSaber.of(
                leaguePositions.find(player.getId(), season, PlayerSeasonMetric.Group.HITTING).orElse(null));
        return new CompareResponse.Line(
                PlayerSummary.of(player, profiles.photoUrl(player), member.team(), today, profiles.returnedKo(player),
                        photoTeam),
                total.games(), total.plateAppearances(), total.avg(), total.homeRuns(), total.rbi(),
                total.stolenBases(), total.ops(), saber instanceof PlayerSaber.Hitter hitter ? hitter.wrcPlus() : null);
    }
}
