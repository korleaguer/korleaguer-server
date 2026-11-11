package com.korleaguer.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NewsArticleRepository extends JpaRepository<NewsArticle, Long> {

    boolean existsBySubjectAndUrlHash(String subject, String urlHash);

    /** 그 선수의 최근 기사(발행 시각이 늦은 순, 같으면 늦게 받은 순) */
    @Query("select a from NewsArticle a where a.subject = :subject order by a.publishedAt desc, a.id desc")
    List<NewsArticle> findLatest(@Param("subject") String subject, Pageable page);
}
