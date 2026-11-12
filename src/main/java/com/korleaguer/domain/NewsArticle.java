package com.korleaguer.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.Instant;

/**
 * 관련 기사 한 건(제목·링크·언론사·발행 시각). 출처가 허락하면(news/sources.yml preview) 더 둔다:
 * leadText는 피드가 준 기사 첫머리, imageUrl·titleKo·summary는 기사 페이지의 대표 사진 주소와 Claude가 쓴
 * 한국어 제목·요약(NewsEnricher).
 * 본문·사진 파일은 저장하지 않는다(사진은 원본 주소만).
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
    private String leadText;
    private String imageUrl;
    private String titleKo;
    private String summary;
    private String summaryModel;
    private Instant enrichedAt;
    private int enrichAttempts;
    private Instant pageReadAt;

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

    public String getLeadText() {
        return leadText;
    }

    public void setLeadText(String leadText) {
        this.leadText = leadText;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public String getTitleKo() {
        return titleKo;
    }

    public String getSummary() {
        return summary;
    }

    public String getSummaryModel() {
        return summaryModel;
    }

    public Instant getEnrichedAt() {
        return enrichedAt;
    }

    public int getEnrichAttempts() {
        return enrichAttempts;
    }

    public Instant getPageReadAt() {
        return pageReadAt;
    }

    /** 제목을 더 나은 표기로 바꾼다(피드가 부호를 뺀 제목을 줄 때, 기사 페이지의 제목으로) */
    public void retitle(String title) {
        if (title != null && !title.isBlank()) {
            this.title = title;
        }
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    /** 기사 페이지를 읽었다. 피드가 주지 않은 앞부분·사진만 채운다(있던 값은 그대로) */
    public void pageRead(String leadText, String imageUrl, Instant at) {
        if (this.leadText == null) {
            this.leadText = leadText;
        }
        if (this.imageUrl == null) {
            this.imageUrl = imageUrl;
        }
        this.pageReadAt = at;
    }

    /**
     * 요약을 붙였다(사진 주소·한국어 제목은 없을 수 있다). summaryModel은 요약한 모델과 지시문 판(예: claude-haiku-5-5/v2)이다.
     * 시도 횟수는 '요약하지 못한 횟수'라 0으로 돌린다. 그래야 지시문을 바꿀 때마다 이 기사를 다시 요약할 수 있다.
     */
    public void enriched(String imageUrl, String titleKo, String summary, String summaryModel, Instant at) {
        if (imageUrl != null) {
            this.imageUrl = imageUrl;
        }
        this.pageReadAt = at;
        this.titleKo = titleKo;
        this.summary = summary;
        this.summaryModel = summaryModel;
        this.enrichedAt = at;
        this.enrichAttempts = 0;
    }

    /** 이번에는 요약하지 못했다(몇 번까지만 다시 해 본다). 옛 요약이 있으면 그대로 둔다 */
    public void enrichFailed(Instant at) {
        this.enrichedAt = at;
        this.enrichAttempts++;
    }
}
