package com.korleaguer.api;

/**
 * 시즌 성적표의 판정(SeasonVerdict). 선언 순서가 홈에서 보이는 순서다(좋은 시즌 먼저).
 * label은 화면에 그대로 나오는 이름이다. 마이너에서 보낸 시즌은 트리플A 밖(더블A 등)에서도 많이 뛰었으면 '마이너에서 보낸 시즌'이다.
 */
public enum SeasonTier {
    GOOD("좋은 시즌"),
    SHORT_GOOD("짧았지만 좋았던 시즌"),
    SOLID("제 몫 한 시즌"),
    MINORS("트리플A에서 보낸 시즌"),
    SHORT("출전이 적었던 시즌"),
    POOR("아쉬운 시즌");

    /** 트리플A 밖에서도 많이 뛴 마이너 시즌의 이름 */
    static final String MINORS_MIXED_LABEL = "마이너에서 보낸 시즌";

    private final String label;

    SeasonTier(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 자랑할 이야기(stories)를 붙이는 판정인지. 출전이 적었던·아쉬운 시즌에는 붙이지 않는다. */
    boolean hasStories() {
        return this != SHORT && this != POOR;
    }
}
