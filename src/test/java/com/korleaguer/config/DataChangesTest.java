package com.korleaguer.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 쓴 SQL이 어느 응답을 바꿀 수 있는지 가른다: 모두(ALL), 홈만(HOME, 기사 제목), 없음(NONE) */
class DataChangesTest {

    @ParameterizedTest
    @CsvSource(delimiter = ';', textBlock = """
            insert into game (game_pk,status_state) values (?,?); ALL
            update appearance set hits=? where id=?; ALL
            delete from minor_game_log mgl1_0 where mgl1_0.player_id=?; ALL
            UPDATE `player` SET level=? WHERE id=?; ALL
              insert into team (id) values (?); ALL
            insert into news_article (title) values (?); HOME
            merge into news_article values (?); HOME
            select g1_0.game_pk from game g1_0; NONE
            insert into ingest_log (kind) values (?); NONE
            update ingest_log set status=? where id=?; NONE
            delete from ingest_log where id=?; NONE
            insert into photo (key) values (?); NONE
            update flyway_schema_history set success=?; NONE
            """)
    void sortsWritesByTheResponsesTheyChange(String sql, DataChanges.Scope expected) {
        assertThat(DataChanges.scopeOf(sql)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', textBlock = """
            update game set status_state=? where game_pk=?; 1; 0
            insert into news_article (title) values (?); 0; 1
            select * from game; 0; 0
            """)
    void inspectReturnsTheSqlAndBumpsTheMatchingVersion(String sql, long all, long home) {
        long allBefore = DataChanges.version();
        long homeBefore = DataChanges.homeVersion();
        assertThat(new DataChanges().inspect(sql)).isEqualTo(sql);
        assertThat(DataChanges.version() - allBefore).isEqualTo(all);
        assertThat(DataChanges.homeVersion() - homeBefore).isEqualTo(home);
    }
}
