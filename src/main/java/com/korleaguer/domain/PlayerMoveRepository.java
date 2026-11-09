package com.korleaguer.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PlayerMoveRepository extends JpaRepository<PlayerMove, Long> {

    /** 한 선수의 이동 기록, 최신순(같은 날이면 MLB 번호가 큰 것부터) */
    @Query("""
            select m from PlayerMove m
            where m.player.id = :playerId
            order by m.moveDate desc, m.mlbTransactionId desc
            """)
    List<PlayerMove> findNewestFirst(long playerId);
}
