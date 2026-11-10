package com.korleaguer.client;

/**
 * 선수의 한 시즌 마이너리그(트리플A ~ 루키) 출전 경기 수. 정규시즌만 센다.
 * hitting은 타자로 나온 경기, pitching은 등판한 경기다. 마이너에서 뛰지 않았으면 둘 다 0이다.
 */
public record MlbMinorGames(int hitting, int pitching) {

    public static final MlbMinorGames NONE = new MlbMinorGames(0, 0);
}
