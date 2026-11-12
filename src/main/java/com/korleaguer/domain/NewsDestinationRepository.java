package com.korleaguer.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface NewsDestinationRepository extends JpaRepository<NewsDestination, Long> {

    /** 그 선수 기사 중 since 뒤에 나온 기사에서 거론된 구단(기사·구단마다 한 줄) */
    @Query("select new com.korleaguer.domain.DestinationMention(d.team, d.stance, a.publishedAt)"
            + " from NewsDestination d, NewsArticle a where a.id = d.articleId and a.subject = :subject"
            + " and a.publishedAt >= :since")
    List<DestinationMention> findMentions(@Param("subject") String subject, @Param("since") Instant since);

    /** 그 기사에서 전에 고른 구단을 지운다(지시문 판이 바뀌어 다시 고를 때) */
    @Modifying
    @Transactional
    @Query("delete from NewsDestination d where d.articleId = :articleId")
    void deleteByArticle(@Param("articleId") Long articleId);
}
