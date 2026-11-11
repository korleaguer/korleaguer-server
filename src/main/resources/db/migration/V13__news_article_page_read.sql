-- 기사 페이지를 읽어 대표 사진(og:image)·앞부분(og:description)을 채운 시각(이슈 #81). 피드가 사진·앞부분을 주지 않는
-- 출처(뉴스 사이트맵 등)의 기사는 페이지를 한 번 읽는다. 열 추가만이라 블루-그린 배포 중 옛 API와 겹쳐도 안전하다.
alter table news_article add column page_read_at datetime(6) null;
