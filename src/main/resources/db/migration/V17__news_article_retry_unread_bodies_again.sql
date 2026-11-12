-- 연합뉴스 기사는 본문이 첫 article 칸 밖에 있어 본문을 못 찾고 다시 실패했다(이슈 #109, V16 뒤 운영 로그).
-- 본문 찾기를 넓혔으니 그사이 요약 없이 포기된 기사를 한 번 더 요약하게 시도 횟수를 0으로 돌린다(다시 실패하면 3번 뒤 또 포기한다).
update news_article
set enrich_attempts = 0
where summary is null
  and enrich_attempts >= 3;
