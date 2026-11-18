package com.korleaguer.domain;

/**
 * 경기 종류: 정규시즌(REGULAR)·포스트시즌(POSTSEASON). 경기 줄 응답(GameLine·MinorGameLine의 seasonType)에 이 이름 그대로 나간다.
 * 경기 종류에 관한 대응표는 모두 여기 한 곳에 둔다: MLB gameType → 종류(fromGameType, 수집), 종류 → DB game.season_type에
 * 저장하는 값(stored, regular·postseason), 저장한 값 → 종류(of, 응답). 저장 값을 쓰는 곳(수집·화면용 조회)도 글자를 따로 적지
 * 않고 stored()로 얻는다. JPQL 글 안의 값만 상수를 쓸 수 없어 글자로 적었다(GameRepository.countFinalRegularGames).
 */
public enum SeasonType {
    REGULAR("regular"),
    POSTSEASON("postseason");

    private final String stored;

    SeasonType(String stored) {
        this.stored = stored;
    }

    /** game.season_type에 저장하는 값(regular·postseason) */
    public String stored() {
        return stored;
    }

    /**
     * MLB gameType → 경기 종류. R → REGULAR, F·D·L·W(와일드카드·디비전 시리즈·리그 챔피언십·월드시리즈) → POSTSEASON.
     * 그 밖(시범경기 S·올스타 A 등)과 null은 null이다(수집이 저장하지 않는 경기).
     */
    public static SeasonType fromGameType(String gameType) {
        if (gameType == null) {
            return null;
        }
        return switch (gameType) {
            case "R" -> REGULAR;
            case "F", "D", "L", "W" -> POSTSEASON;
            default -> null;
        };
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
