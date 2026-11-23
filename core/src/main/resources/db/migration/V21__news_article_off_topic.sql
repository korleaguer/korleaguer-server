-- 그 선수가 주인공이 아닌 기사(다른 이야기에 이름만 곁들여 나옴)인지(이슈 #129). 행선지 구단 분류와 같은 AI 호출이 판단한다.
-- true면 홈·기사 페이지 목록, 거론 수, 성적표 '이번 겨울'에서 뺀다. 아직 판단 전이면 null(보인다).
-- 기본값 없는 새 칸이라 블루-그린 배포 중 옛 API와 겹쳐도 안전하다.
alter table news_article add column off_topic boolean null;
