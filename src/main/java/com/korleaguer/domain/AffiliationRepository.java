package com.korleaguer.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface AffiliationRepository extends JpaRepository<Affiliation, Long> {

    /** 모든 선수의 현재 소속(선수 id 순) */
    @Query("""
            select a from Affiliation a
            join fetch a.player p
            join fetch a.team
            where a.endDate is null
            order by p.id
            """)
    List<Affiliation> findAllCurrent();

    Optional<Affiliation> findByPlayerAndEndDateIsNull(Player player);
}
