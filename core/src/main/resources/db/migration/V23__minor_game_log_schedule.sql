-- 선수 화면 '경기 기록'에 마이너 경기를 메이저 경기와 같은 모양으로 보이려고 더한 칸(이슈 #145).
-- team_id·opponent_id: MLB 팀 번호(gameLog의 team·opponent, 웹이 로고를 그린다)
-- 아래는 MLB 일정(schedule?gamePks=, 여러 경기 한 번에)에서 받는다. 아직 묻지 않았으면 null이고,
-- 끝나 점수를 받은 경기는 다시 묻지 않는다(경기별 기록을 통째로 바꿀 때도 옮겨 담는다, IngestWriter.saveMinorGameLog).
-- start_time_utc: 시작 시각, status_state: GameStatuses 값(final 등), team_score·opponent_score: 선수 팀·상대 팀 점수,
-- team_place·opponent_place: 팀 지명(MLB franchiseName). 한국어 이름(roster/minor-teams.yml)이 없는 팀에 쓴다.
-- 기본값 없는 새 칸이라 블루-그린 배포 중 옛 API와 겹쳐도 안전하다(옛 API가 다시 쓰면 null이 되고 새 API가 다시 묻는다).
alter table minor_game_log add column team_id bigint null;
alter table minor_game_log add column opponent_id bigint null;
alter table minor_game_log add column start_time_utc datetime(6) null;
alter table minor_game_log add column status_state varchar(30) null;
alter table minor_game_log add column team_score int null;
alter table minor_game_log add column opponent_score int null;
alter table minor_game_log add column team_place varchar(100) null;
alter table minor_game_log add column opponent_place varchar(100) null;
