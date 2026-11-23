package com.korleaguer.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

public interface KoreanMlbSeasonRepository extends JpaRepository<KoreanMlbSeason, Long> {

    /** 한 선수·묶음의 시즌 줄 전부 */
    List<KoreanMlbSeason> findByMlbIdAndStatGroup(long mlbId, PlayerSeasonMetric.Group statGroup);

    /** 시즌 줄이 하나라도 있는 선수(MLB 번호) */
    @Query("select distinct s.mlbId from KoreanMlbSeason s")
    List<Long> findMlbIds();

    /** since 뒤에 받은 줄이 있는 선수(MLB 번호) */
    @Query("select distinct s.mlbId from KoreanMlbSeason s where s.fetchedAt >= :since")
    List<Long> findMlbIdsFetchedSince(Instant since);
}
