package com.korleaguer.domain;

/**
 * 경기 종류: 정규시즌(REGULAR)·포스트시즌(POSTSEASON). 경기 줄 응답(GameLine·MinorGameLine의 seasonType)에 이 이름 그대로 나간다.
 * DB(game.season_type)에는 수집 때 MLB gameType으로 정한 소문자 값(R → regular, F·D·L·W → postseason,
 * IngestWriter.seasonType)이 들어 있고, of()가 그 값을 이 종류로 바꾼다.
 */
public enum SeasonType {
    REGULAR("regular"),
    POSTSEASON("postseason");

    private final String stored;

    SeasonType(String stored) {
        this.stored = stored;
    }

    /** game.season_type에 저장한 값 → 경기 종류. null이거나 모르는 값이면 null */
    public static SeasonType of(String stored) {
        for (SeasonType type : values()) {
            if (type.stored.equals(stored)) {
                return type;
            }
        }
        return null;
    }
}
