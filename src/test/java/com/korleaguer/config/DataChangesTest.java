package com.korleaguer.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 응답에 나오는 표에 쓴 SQL만 데이터 변경으로 센다 */
class DataChangesTest {

    @ParameterizedTest
    @CsvSource(delimiter = ';', textBlock = """
            insert into game (game_pk,status_state) values (?,?); true
            update appearance set hits=? where id=?; true
            delete from minor_game_log mgl1_0 where mgl1_0.player_id=?; true
            UPDATE `player` SET level=? WHERE id=?; true
            merge into news_article values (?); true
              insert into team (id) values (?); true
            select g1_0.game_pk from game g1_0; false
            insert into ingest_log (kind) values (?); false
            update ingest_log set status=? where id=?; false
            delete from ingest_log where id=?; false
            insert into photo (key) values (?); false
            update flyway_schema_history set success=?; false
            """)
    void countsWritesToTablesTheScreensShow(String sql, boolean expected) {
        assertThat(DataChanges.countsAsChange(sql)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', textBlock = """
            update game set status_state=? where game_pk=?; 1
            select * from game; 0
            """)
    void inspectReturnsTheSqlAndBumpsTheVersionForWrites(String sql, long bump) {
        long before = DataChanges.version();
        assertThat(new DataChanges().inspect(sql)).isEqualTo(sql);
        assertThat(DataChanges.version() - before).isEqualTo(bump);
    }
}
