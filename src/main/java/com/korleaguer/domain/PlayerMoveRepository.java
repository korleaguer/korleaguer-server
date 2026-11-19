package com.korleaguer.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface PlayerMoveRepository extends JpaRepository<PlayerMove, Long> {

    /** 한 선수의 이동 기록, 최신순(같은 날이면 MLB 번호가 큰 것부터) */
    @Query("""
            select m from PlayerMove m
            where m.player.id = :playerId
            order by m.moveDate desc, m.mlbTransactionId desc
            """)
    List<PlayerMove> findNewestFirst(long playerId);

    /** 여러 선수의 since(그날 포함) 이후 이동 기록, 오래된 것부터(같은 날이면 MLB 번호가 작은 것부터) */
    @Query("""
            select m from PlayerMove m
            where m.player.id in :playerIds and m.moveDate >= :since
            order by m.moveDate asc, m.mlbTransactionId asc
            """)
    List<PlayerMove> findSinceOldestFirst(Collection<Long> playerIds, LocalDate since);
}
