package com.korleaguer.domain;

import org.springframework.data.domain.Pageable;
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

    /**
     * 선발·교체를 아직 모르는 기록이 있는 굳은(settled) 경기 중, 박스스코어로 채워 보지 않은 경기(최근 경기부터).
     * 지난 경기를 뒤에서 천천히 채울 때 쓴다. 굳기 전 경기는 경기 갱신·재확인에서 박스스코어를 받을 때 채운다.
     * 채워 본 경기라도 그 뒤 시즌 백필로 새 기록이 생기면 다시 채워 보지 않은 경기가 된다(IngestWriter.saveGameLog).
     */
    @Query("""
            select distinct g from Appearance a
            join a.game g
            where a.lineup is null and g.settled = true and g.lineupsChecked = false
            order by g.startTimeUtc desc
            """)
    List<Game> findGamesMissingLineups(Pageable page);

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
