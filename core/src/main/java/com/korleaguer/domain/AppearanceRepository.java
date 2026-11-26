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
     * 한 선수가 그 시즌 seasonTypes 경기에 남긴 기록과, 같은 경기에 다른 선수가 남긴 기록(같은 경기 맞대결, 이슈 #217).
     * 기록은 우리 선수 것만 저장하므로 한 경기에 줄이 둘 이상이면 우리 선수가 같이 나온 경기다
     */
    @Query("""
            select a from Appearance a
            join fetch a.player
            join fetch a.game g
            join fetch g.homeTeam
            join fetch g.awayTeam
            join fetch a.team
            where g.season = :season and g.seasonType in :seasonTypes
              and g in (select m.game from Appearance m where m.player.id = :playerId)
            """)
    List<Appearance> findSeasonInGamesOf(long playerId, int season, Collection<String> seasonTypes);

    /**
     * 선발·교체를 아직 모르는 기록이 있는 굳은(settled) 경기 중, 박스스코어로 채워 보지 않은 경기(최근 경기부터).
     * 지난 경기를 뒤에서 천천히 채울 때 쓴다. 굳기 전 경기는 경기 갱신·재확인에서 박스스코어를 받을 때 채운다.
     * 채워 본 경기라도 그 뒤 시즌 백필로 새 기록이 생기면 다시 채워 보지 않은 경기가 된다(GameWriter.saveGameLog).
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

    /** 선수마다 now까지 뛴 경기 중 가장 늦게 시작한 경기의 시작 시각. 뛴 경기가 없는 선수는 빠진다 */
    @Query("""
            select new com.korleaguer.domain.PlayerLastStart(a.player.id, max(g.startTimeUtc)) from Appearance a
            join a.game g
            where a.player.id in :playerIds and g.startTimeUtc <= :now
            group by a.player.id
            """)
    List<PlayerLastStart> findLastStartTimes(Collection<Long> playerIds, Instant now);

    /** 선수가 그 메이저 경기에 기록을 남겼는지(경기 상세가 그 선수의 경기인지 볼 때) */
    boolean existsByPlayer_IdAndGame_GamePk(Long playerId, Long gamePk);

    /** 한 경기에 기록을 남긴 우리 선수들(경기 상세를 받을 선수) */
    @Query("""
            select a from Appearance a
            join fetch a.player
            where a.game.gamePk = :gamePk
            """)
    List<Appearance> findWithPlayerByGame(long gamePk);

    /**
     * 끝난 경기의 기록 중 굳은(complete) 경기 상세가 아직 없는 것(최근 경기부터). 지난 경기 상세를 뒤에서 조금씩 채울 때 쓴다.
     * 아직 굳힐 때가 안 된 상세(받은 지 하루가 안 된 경기)도 섞여 나오므로 부르는 쪽이 가른다(ingest.GameDetailIngest).
     * 다 채운 뒤에는 최근 하루 안팎 경기만 나온다.
     */
    @Query("""
            select a from Appearance a
            join fetch a.player p
            join fetch a.game g
            where g.statusState = 'final' and p.mlbId is not null
              and not exists (select d.id from PlayerGameDetail d
                              where d.playerId = p.id and d.gamePk = g.gamePk and d.complete = true)
            order by g.startTimeUtc desc
            """)
    List<Appearance> findFinalsWithoutCompleteDetail();

    /** 경기 기록이 있는 시즌(연도) */
    @Query("select distinct g.season from Appearance a join a.game g")
    List<Integer> findSeasons();
}
