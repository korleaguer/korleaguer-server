package com.korleaguer.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.Instant;

/**
 * 관련 기사 한 건(제목·링크·언론사·발행 시각만). 본문·사진·요약은 저장하지 않는다.
 * subject는 기사가 다루는 포스팅 선수의 한글 이름이다(포스팅 선수는 아직 명단에 없어 선수 번호가 없다).
 * 같은 선수에 같은 주소(urlHash)는 한 번만 둔다. 언론사가 내려 달라고 하면 그 줄을 지운다.
 */
@Entity
public class NewsArticle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String subject;
    private String url;
    private String urlHash;
    private String title;
    private String publisher;
    private Instant publishedAt;
    private Instant fetchedAt;

    protected NewsArticle() {
    }

    public NewsArticle(String subject, String url, String urlHash, String title, String publisher,
                       Instant publishedAt, Instant fetchedAt) {
        this.subject = subject;
        this.url = url;
        this.urlHash = urlHash;
        this.title = title;
        this.publisher = publisher;
        this.publishedAt = publishedAt;
        this.fetchedAt = fetchedAt;
    }

    public Long getId() {
        return id;
    }

    public String getSubject() {
        return subject;
    }

    public String getUrl() {
        return url;
    }

    public String getUrlHash() {
        return urlHash;
    }

    public String getTitle() {
        return title;
    }

    public String getPublisher() {
        return publisher;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }
}
