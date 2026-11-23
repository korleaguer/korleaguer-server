package com.korleaguer.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface AffiliationRepository extends JpaRepository<Affiliation, Long> {

    /** 모든 선수의 지금 소속(선수 id 순). 소속이 끝난 선수(FA 등)는 빠진다. 수집(경기를 갱신할 팀)이 쓴다. */
    @Query("""
            select a from Affiliation a
            join fetch a.player p
            join fetch a.team
            where a.endDate is null
            order by p.id
            """)
    List<Affiliation> findAllCurrent();

    /**
     * 모든 선수의 가장 최근 소속(선수 id 순). 지금 소속이 있으면 그것, 소속이 끝났으면(FA 등) 마지막 소속(endDate가 있다)이다.
     * 화면(홈·선수 목록·내 선수)이 쓴다. 시즌 기록이 있는 FA 선수가 빠지지 않게 한다.
     */
    @Query("""
            select a from Affiliation a
            join fetch a.player p
            join fetch a.team
            order by p.id
            """)
    List<Affiliation> findAllLatest();

    /** 선수의 소속 줄(선수마다 한 줄). 지금 소속, 끝났으면 마지막 소속 */
    Optional<Affiliation> findByPlayer(Player player);
}
