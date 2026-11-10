package com.korleaguer.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface GameRepository extends JpaRepository<Game, Long> {

    /** 주어진 팀들의 경기 중 [from, to) 사이에 시작한 경기 */
    @Query("""
            select g from Game g
            join fetch g.homeTeam
            join fetch g.awayTeam
            where (g.homeTeam.mlbTeamId in :teamIds or g.awayTeam.mlbTeamId in :teamIds)
              and g.startTimeUtc >= :from and g.startTimeUtc < :to
            """)
    List<Game> findTeamGamesBetween(Collection<Long> teamIds, Instant from, Instant to);

    /** 주어진 팀들의 끝난 경기 중 가장 늦게 시작한 경기의 시작 시각 */
    @Query("""
            select max(g.startTimeUtc) from Game g
            where (g.homeTeam.mlbTeamId in :teamIds or g.awayTeam.mlbTeamId in :teamIds)
              and g.statusState = 'final' and g.startTimeUtc <= :now
            """)
    Optional<Instant> findLastFinalStartTime(Collection<Long> teamIds, Instant now);

    /**
     * 주어진 팀들의 경기 중 [from, to) 사이에 시작했는데 아직 끝나지 않은 것으로 남은 경기(끝남·연기·취소가 아님) 중
     * 가장 이른 시작 시각. 갱신이 밀려 결과를 못 받은 경기를 찾을 때 쓴다.
     */
    @Query("""
            select min(g.startTimeUtc) from Game g
            where (g.homeTeam.mlbTeamId in :teamIds or g.awayTeam.mlbTeamId in :teamIds)
              and g.startTimeUtc >= :from and g.startTimeUtc < :to
              and g.statusState not in ('final', 'postponed', 'canceled')
            """)
    Optional<Instant> findFirstUnfinishedStartTime(Collection<Long> teamIds, Instant from, Instant to);

    /** 주어진 팀들의 한 시즌 경기 중 seasonType(regular, postseason) 경기 */
    @Query("""
            select g from Game g
            join fetch g.homeTeam
            join fetch g.awayTeam
            where (g.homeTeam.mlbTeamId in :teamIds or g.awayTeam.mlbTeamId in :teamIds)
              and g.season = :season and g.seasonType = :seasonType
            """)
    List<Game> findTeamSeasonGames(Collection<Long> teamIds, int season, String seasonType);

    /** 한 팀이 한 시즌에 치른(끝난) 정규시즌 경기 수 */
    @Query("""
            select count(g) from Game g
            where (g.homeTeam.mlbTeamId = :teamId or g.awayTeam.mlbTeamId = :teamId)
              and g.season = :season and g.seasonType = 'regular' and g.statusState = 'final'
            """)
    long countFinalRegularGames(long teamId, int season);

    /** 끝났지만 아직 굳지 않은(settled가 아닌) 경기 */
    @Query("""
            select g from Game g
            join fetch g.homeTeam
            join fetch g.awayTeam
            where g.statusState = 'final' and g.settled = false
            """)
    List<Game> findUnsettledFinals();

    /** 주어진 팀들의 경기 중 [from, to) 사이에 시작한 경기가 있는지(시즌 중인지 볼 때 쓴다) */
    @Query("""
            select count(g) > 0 from Game g
            where (g.homeTeam.mlbTeamId in :teamIds or g.awayTeam.mlbTeamId in :teamIds)
              and g.startTimeUtc >= :from and g.startTimeUtc < :to
            """)
    boolean existsTeamGameBetween(Collection<Long> teamIds, Instant from, Instant to);
}
