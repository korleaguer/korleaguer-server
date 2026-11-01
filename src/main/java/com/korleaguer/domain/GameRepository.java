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
            where (g.homeTeam.bdlTeamId in :teamIds or g.awayTeam.bdlTeamId in :teamIds)
              and g.startTimeUtc >= :from and g.startTimeUtc < :to
            """)
    List<Game> findTeamGamesBetween(Collection<Long> teamIds, Instant from, Instant to);

    /** 주어진 팀들의 끝난 경기 중 가장 늦게 시작한 경기의 시작 시각 */
    @Query("""
            select max(g.startTimeUtc) from Game g
            where (g.homeTeam.bdlTeamId in :teamIds or g.awayTeam.bdlTeamId in :teamIds)
              and g.statusState = 'final' and g.startTimeUtc <= :now
            """)
    Optional<Instant> findLastFinalStartTime(Collection<Long> teamIds, Instant now);
}
