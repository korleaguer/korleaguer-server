package com.korleaguer.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.Instant;

/**
 * 하이라이트 영상 영어 제목 하나의 한국어 번역(이슈 #199). 같은 영어 제목은 한 줄이고,
 * promptVersion은 옮긴 모델과 지시문 판이다(지금 판과 다르면 다시 옮겨 덮어쓴다).
 */
@Entity
public class HighlightTitle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String titleEn;
    private String titleKo;
    private String promptVersion;
    private Instant createdAt;

    protected HighlightTitle() {
    }

    public HighlightTitle(String titleEn) {
        this.titleEn = titleEn;
    }

    public void translated(String titleKo, String promptVersion, Instant createdAt) {
        this.titleKo = titleKo;
        this.promptVersion = promptVersion;
        this.createdAt = createdAt;
    }

    public String getTitleEn() {
        return titleEn;
    }

    public String getTitleKo() {
        return titleKo;
    }

    public String getPromptVersion() {
        return promptVersion;
    }
}
