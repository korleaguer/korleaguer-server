package com.korleaguer.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface NewsArticleRepository extends JpaRepository<NewsArticle, Long> {

    boolean existsBySubjectAndUrlHash(String subject, String urlHash);

    /** 그 선수의 최근 기사(발행 시각이 늦은 순, 같으면 늦게 받은 순) */
    @Query("select a from NewsArticle a where a.subject = :subject order by a.publishedAt desc, a.id desc")
    List<NewsArticle> findLatest(@Param("subject") String subject, Pageable page);

    /** 아직 요약이 없는 그 언론사들의 기사(시도 횟수가 남고 since 뒤에 나온 것, 최근 순) */
    @Query("select a from NewsArticle a where a.publisher in :publishers and a.summary is null"
            + " and a.enrichAttempts < :maxAttempts and a.publishedAt >= :since order by a.publishedAt desc, a.id desc")
    List<NewsArticle> findToEnrich(@Param("publishers") Collection<String> publishers,
                                   @Param("maxAttempts") int maxAttempts, @Param("since") Instant since, Pageable page);
}
