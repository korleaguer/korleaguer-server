package com.korleaguer.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AppearanceRepository extends JpaRepository<Appearance, Long> {

    Optional<Appearance> findByPlayerAndGame(Player player, Game game);

    /** 선수가 가장 늦게 시작한 경기에 뛴 기록(그때의 팀을 알 때 쓴다) */
    Optional<Appearance> findFirstByPlayerOrderByGameStartTimeUtcDesc(Player player);

    /** 주어진 선수들의 기록 중 경기가 [from, to) 사이에 시작한 것 */
    @Query("""
            select a from Appearance a
            join fetch a.game g
            join fetch g.homeTeam
            join fetch g.awayTeam
            join fetch a.team
            where a.player.id in :playerIds
              and g.startTimeUtc >= :from and g.startTimeUtc < :to
            """)
    List<Appearance> findWithGameBetween(Collection<Long> playerIds, Instant from, Instant to);

    /** 주어진 선수들의 한 시즌 기록 중 seasonTypes(regular, postseason 등) 경기 것 */
    @Query("""
            select a from Appearance a
            join fetch a.game g
            join fetch a.team
            where a.player.id in :playerIds
              and g.season = :season and g.seasonType in :seasonTypes
            """)
    List<Appearance> findSeason(Collection<Long> playerIds, int season, Collection<String> seasonTypes);

    /** 주어진 선수들의 기록 중 주어진 경기들 것 */
    @Query("""
            select a from Appearance a
            where a.player.id in :playerIds and a.game.gamePk in :gamePks
            """)
    List<Appearance> findByPlayersAndGames(Collection<Long> playerIds, Collection<Long> gamePks);

    /** 주어진 선수들이 뛴 경기 중 now까지 가장 늦게 시작한 경기의 시작 시각 */
    @Query("""
            select max(g.startTimeUtc) from Appearance a
            join a.game g
            where a.player.id in :playerIds and g.startTimeUtc <= :now
            """)
    Optional<Instant> findLastStartTime(Collection<Long> playerIds, Instant now);
}
