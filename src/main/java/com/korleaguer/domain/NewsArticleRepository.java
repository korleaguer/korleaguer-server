package com.korleaguer.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface NewsArticleRepository extends JpaRepository<NewsArticle, Long> {

    boolean existsBySubjectAndUrlHash(String subject, String urlHash);

    Optional<NewsArticle> findBySubjectAndUrlHash(String subject, String urlHash);

    /** 그 선수의 최근 기사(발행 시각이 늦은 순, 같으면 늦게 받은 순) */
    @Query("select a from NewsArticle a where a.subject = :subject order by a.publishedAt desc, a.id desc")
    List<NewsArticle> findLatest(@Param("subject") String subject, Pageable page);

    /** 그 선수의 AI 요약이 있는 기사(최근 순) */
    @Query("select a from NewsArticle a where a.subject = :subject and a.summary is not null"
            + " order by a.publishedAt desc, a.id desc")
    List<NewsArticle> findLatestSummarized(@Param("subject") String subject, Pageable page);

    /** 피드가 앞부분이나 사진을 주지 않아 기사 페이지를 읽어야 하는 그 언론사들의 기사(아직 읽지 않았고 시도가 남은 것) */
    @Query("select a from NewsArticle a where a.publisher in :publishers and a.pageReadAt is null"
            + " and (a.leadText is null or a.imageUrl is null) and a.enrichAttempts < :maxAttempts"
            + " and a.publishedAt >= :since order by a.publishedAt desc, a.id desc")
    List<NewsArticle> findNeedingPage(@Param("publishers") Collection<String> publishers,
                                      @Param("maxAttempts") int maxAttempts, @Param("since") Instant since, Pageable page);

    /** 아직 요약이 없는 그 언론사들의 기사(시도 횟수가 남고 since 뒤에 나온 것, 최근 순) */
    @Query("select a from NewsArticle a where a.publisher in :publishers and a.summary is null"
            + " and a.enrichAttempts < :maxAttempts and a.publishedAt >= :since order by a.publishedAt desc, a.id desc")
    List<NewsArticle> findToEnrich(@Param("publishers") Collection<String> publishers,
                                   @Param("maxAttempts") int maxAttempts, @Param("since") Instant since, Pageable page);
}
