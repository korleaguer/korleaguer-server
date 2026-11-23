package com.korleaguer.domain;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlayerGameDetailRepository extends JpaRepository<PlayerGameDetail, Long> {

    Optional<PlayerGameDetail> findByPlayerIdAndGamePk(long playerId, long gamePk);

    /** 번호가 before보다 작은 줄을 큰 번호부터 limit개(저장된 상세를 한 묶음씩 훑을 때: 하이라이트 제목 번역) */
    List<PlayerGameDetail> findByIdLessThanOrderByIdDesc(long before, Limit limit);
}
