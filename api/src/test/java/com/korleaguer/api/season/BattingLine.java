package com.korleaguer.api.season;

import com.korleaguer.domain.Batting;

/**
 * 테스트용 타격 기록. 숫자 14개를 늘어놓지 않고 쓰는 칸의 이름만 적는다: {@code BattingLine.atBats(4).hits(2).build()}.
 * 적지 않은 칸은 0이고, 타석과 루타는 적은 값으로 계산한다(MLB 응답도 늘 이 두 칸을 채워 준다).
 */
final class BattingLine {

    private final int atBats;
    private int runs;
    private int hits;
    private Integer doubles = 0;
    private Integer triples = 0;
    private int homeRuns;
    private int rbi;
    private int walks;
    private int strikeouts;
    private int hitByPitch;
    private int sacFlies;
    private boolean totalsKnown = true;
    private Integer plateAppearances;
    private Integer totalBases;

    private BattingLine(int atBats) {
        this.atBats = atBats;
    }

    static BattingLine atBats(int atBats) {
        return new BattingLine(atBats);
    }

    BattingLine runs(int runs) {
        this.runs = runs;
        return this;
    }

    BattingLine hits(int hits) {
        this.hits = hits;
        return this;
    }

    BattingLine doubles(int doubles) {
        this.doubles = doubles;
        return this;
    }

    BattingLine homeRuns(int homeRuns) {
        this.homeRuns = homeRuns;
        return this;
    }

    BattingLine rbi(int rbi) {
        this.rbi = rbi;
        return this;
    }

    BattingLine walks(int walks) {
        this.walks = walks;
        return this;
    }

    BattingLine strikeouts(int strikeouts) {
        this.strikeouts = strikeouts;
        return this;
    }

    BattingLine hitByPitch(int hitByPitch) {
        this.hitByPitch = hitByPitch;
        return this;
    }

    BattingLine sacFlies(int sacFlies) {
        this.sacFlies = sacFlies;
        return this;
    }

    /** 2루타·3루타 칸이 없는 옛 기록 */
    BattingLine withoutExtraBases() {
        this.doubles = null;
        this.triples = null;
        return this;
    }

    /** 타석·루타 칸이 비어 있는 기록 */
    BattingLine withoutTotals() {
        this.totalsKnown = false;
        return this;
    }

    /** 다른 칸에서 계산한 것과 다른 타석(희생번트 등) */
    BattingLine plateAppearances(int plateAppearances) {
        this.plateAppearances = plateAppearances;
        return this;
    }

    BattingLine totalBases(int totalBases) {
        this.totalBases = totalBases;
        return this;
    }

    Batting build() {
        int extra = doubles == null ? 0 : doubles + 2 * triples;
        int bases = hits + extra + 3 * homeRuns;
        return new Batting(atBats, runs, hits, doubles, triples, homeRuns, rbi, walks, strikeouts, hitByPitch, 0, sacFlies,
                !totalsKnown ? null : plateAppearances != null ? plateAppearances : atBats + walks + hitByPitch + sacFlies,
                !totalsKnown ? null : totalBases != null ? totalBases : Integer.valueOf(bases));
    }
}
