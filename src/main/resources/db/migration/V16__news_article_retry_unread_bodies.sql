-- 조선일보(Arc XP)는 본문이 HTML 문단이 아니라 페이지 속 JSON에 있어서 본문을 못 찾고 시도 3번 뒤 포기됐다(이슈 #107).
-- 본문 읽기를 고쳤으니 요약 없이 포기된 기사를 한 번 더 요약하게 시도 횟수를 0으로 돌린다(다시 실패하면 3번 뒤 또 포기한다).
update news_article
set enrich_attempts = 0
where summary is null
  and enrich_attempts >= 3;
