package com.korleaguer.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 이동 기록 한국어 이름. 설명 글은 MLB 실제 응답(고우석 2025~2026 이동 기록)에서 가져왔다. */
class MoveLabelsTest {

    @ParameterizedTest
    @DisplayName("이동 종류마다 강등·콜업·트레이드·방출 같은 우리말 이름을 붙인다")
    @CsvSource(delimiter = '|', textBlock = """
            OPT | Optioned                  | Minnesota Twins optioned RHP Woo-Suk Go to St. Paul Saints.                   | 마이너 강등(옵션)
            CU  | Recalled                  | Minnesota Twins recalled RHP Woo-Suk Go from St. Paul Saints.                 | 메이저 콜업
            SE  | Selected                  | Minnesota Twins selected the contract of RHP Woo-Suk Go from St. Paul Saints. | 메이저 콜업
            OUT | Outrighted                | Minnesota Twins sent RHP Woo-Suk Go outright to St. Paul Saints.              | 마이너 이관(아웃라이트)
            DES | Designated for Assignment | Minnesota Twins designated RHP Woo-Suk Go for assignment.                     | 지명 할당(DFA)
            TR  | Trade                     | Detroit Tigers traded RHP Woo-Suk Go to Minnesota Twins for cash.             | 트레이드
            SFA | Signed as Free Agent      | Detroit Tigers signed free agent RHP Woo-Suk Go to a minor league contract.   | 계약
            SGN | Signed                    | Somebody signed somebody.                                                     | 계약
            DFA | Declared Free Agency      | RHP Woo-Suk Go elected free agency.                                           | FA 선언
            REL | Released                  | Jacksonville Jumbo Shrimp released RHP Woo-Suk Go.                            | 방출
            CLW | Claimed Off Waivers       | Atlanta Braves claimed SS Somebody off waivers.                               | 웨이버 클레임
            SU  | Suspension                | RHP Woo-Suk Go suspended.                                                     | 출장 정지
            ASG | Assigned                  | Pensacola Blue Wahoos sent RHP Woo-Suk Go on a rehab assignment to Beloit Sky Carp. | 재활 경기 배정
            ASG | Assigned                  | Miami Marlins invited non-roster RHP Woo-Suk Go to spring training.           | 스프링캠프 초청
            ASG | Assigned                  | RHP Woo-Suk Go assigned to Toledo Mud Hens.                                   | 팀 배정
            """)
    void typeCodes(String typeCode, String typeDesc, String description, String expected) {
        // when // then
        assertThat(MoveLabels.korean(typeCode, typeDesc, description)).isEqualTo(expected);
    }

    @ParameterizedTest
    @DisplayName("부상자 명단과 출장 정지에 오르고 풀리고 옮긴 일에 우리말 이름을 붙인다")
    @CsvSource(delimiter = '|', textBlock = """
            Pensacola Blue Wahoos placed RHP Woo-Suk Go on the 7-day injured list.                     | 부상자 명단 등재
            Toledo Mud Hens activated RHP Woo-Suk Go from the 7-day injured list.                      | 부상자 명단 해제
            Some Club reinstated RHP Somebody from the 15-day injured list.                            | 부상자 명단 해제
            Some Club transferred RHP Somebody from the 15-day injured list to the 60-day injured list. | 부상자 명단 이동
            Some Club suspended RHP Somebody.                                                          | 출장 정지
            Some Club reinstated RHP Somebody from the suspended list.                                 | 출장 정지 해제
            """)
    void statusChangesThatMatter(String description, String expected) {
        // when // then
        assertThat(MoveLabels.korean("SC", "Status Change", description)).isEqualTo(expected);
    }

    @ParameterizedTest
    @DisplayName("부상이나 출장 정지와 상관없는 명단 상태 변화에는 이름을 붙이지 않는다")
    @CsvSource(delimiter = '|', textBlock = """
            RHP Woo-Suk Go roster status changed by St. Paul Saints.
            St. Paul Saints activated RHP Woo-Suk Go.
            Korea activated RHP Woo-Suk Go.
            """)
    void otherStatusChangesAreDropped(String description) {
        // when // then
        assertThat(MoveLabels.korean("SC", "Status Change", description)).isNull();
    }

    @Test
    @DisplayName("등번호 변경에는 이동 이름을 붙이지 않는다")
    void numberChangeIsDropped() {
        // when // then
        assertThat(MoveLabels.korean("NUM", "Number Change", "Los Angeles Dodgers 2B Hyeseong Kim changed number to 6.")).isNull();
    }

    @Test
    @DisplayName("모르는 이동 종류는 영어 이름을 그대로 쓰고, 계약을 뜻하면 계약으로 쓴다")
    void unknownTypeKeepsEnglishDescription() {
        // when // then
        assertThat(MoveLabels.korean("XYZ", "Some New Type", null)).isEqualTo("Some New Type");
        assertThat(MoveLabels.korean("XYZ", "Signed - Minor League", null)).isEqualTo("계약");
        assertThat(MoveLabels.korean(null, null, null)).isNull();
    }

    @Test
    @DisplayName("이동 종류 표기의 대소문자와 앞뒤 공백은 따지지 않는다")
    void codeIsCaseInsensitive() {
        // when // then
        assertThat(MoveLabels.korean(" opt ", "Optioned", null)).isEqualTo("마이너 강등(옵션)");
    }
}
