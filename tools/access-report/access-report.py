#!/usr/bin/env python3
"""코리거 접근 기록(Caddy access.log) 하루치 집계.

서버의 /srv/korleaguer/logs 에 쌓이는 JSON 줄(IP는 /24로 잘려 있다)을 읽어
경로별 방문 수, 순방문자, 앱 비율, 제외한 요청 수를 표로 낸다. 표준 라이브러리만 쓴다.

  # 서버에서(기록 폴더는 deploy 계정만 읽으므로 sudo). 스크립트를 표준입력으로 넘기면 서버에 설치하지 않아도 된다.
  ssh korleaguer-admin 'sudo -n python3 - --date 2026-11-19' < tools/access-report/access-report.py
  ssh korleaguer-admin 'sudo -n python3 - --days 7 --path /records' < tools/access-report/access-report.py

옵션
  --date YYYY-MM-DD   끝날(한국 시간, 기본 오늘)
  --days N            끝날까지 N일을 센다(기본 1)
  --path PREFIX       그 경로(예 /records)를 본 사람 수·전체 대비 비율·재방문 비율을 더 낸다
  --full              경로별 표를 첫 구간으로 묶지 않고 전체 경로(쿼리 뺀 것)로 낸다
  --top N             경로별 표 줄 수(기본 30)
  --log-dir DIR       기록 폴더(기본 /srv/korleaguer/logs)

세는 규칙
  - 방문(페이지 보기) = GET이고 상태 400 미만인 요청 중 아래를 뺀 것.
      · Next 미리 받기: Next-Router-Prefetch 머리글이 있는 요청
      · 봇·도구: User-Agent가 비었거나 bot/crawl/spider/curl/wget/python 등이 든 요청
      · 페이지가 아닌 경로: /_next/, /api/, /healthz, robots.txt, sitemap.xml, favicon
    화면 이동 때 Next가 부르는 ?_rsc= 요청은 방문으로 센다(첫 문서 요청과 같은 사람이면 순방문자는 하나).
  - 순방문자 = 마스킹된 IP(client_ip) + User-Agent 해시. 쿠키 없이 센 추정이다(같은 /24의 같은 기종은 한 명, 와이파이↔데이터를
    오가는 사람은 둘로 센다). 날짜별로 따로 센 뒤 기간 합계는 같은 해시를 하나로 본다.
  - 앱 = User-Agent에 KORLeaguerApp이 든 방문자.
  - 재방문 = 기간 안에서 서로 다른 날 2일 이상 본 사람.
IP는 해시로만 쓰고 출력하지 않는다.
"""
import argparse
import glob
import gzip
import hashlib
import json
import os
import re
import sys
import unicodedata
from collections import Counter, defaultdict
from datetime import datetime, timedelta, timezone

KST = timezone(timedelta(hours=9))
BOT = re.compile(
    r"bot\b|bot/|crawl|spider|slurp|curl/|wget|python-|httpx|aiohttp|go-http|java/|axios|node-fetch|headless|lighthouse"
    r"|facebookexternalhit|uptime|pingdom|feedfetcher|bingpreview|ahrefs|semrush",
    re.IGNORECASE,
)
NOT_PAGE = re.compile(r"^/(?:_next/|api/|healthz|robots\.txt|sitemap.*\.xml|favicon|apple-touch-icon|manifest)")


def open_log(path):
    return gzip.open(path, "rt", encoding="utf-8", errors="replace") if path.endswith(".gz") else open(path, encoding="utf-8", errors="replace")


def pad(text, width, right=False):
    """한글은 두 칸으로 세어 표 칸을 맞춘다."""
    w = sum(2 if unicodedata.east_asian_width(c) in "WF" else 1 for c in text)
    fill = " " * max(width - w, 0)
    return fill + text if right else text + fill


def header(headers, name):
    for k, v in headers.items():
        if k.lower() == name.lower():
            return v[0] if isinstance(v, list) and v else (v if isinstance(v, str) else "")
    return ""


def main():
    ap = argparse.ArgumentParser(description="코리거 접근 기록 집계")
    ap.add_argument("--date", help="끝날 YYYY-MM-DD(한국 시간, 기본 오늘)")
    ap.add_argument("--days", type=int, default=1)
    ap.add_argument("--path", help="따로 볼 경로 앞부분(예 /records)")
    ap.add_argument("--full", action="store_true")
    ap.add_argument("--top", type=int, default=30)
    ap.add_argument("--log-dir", default="/srv/korleaguer/logs")
    a = ap.parse_args()

    end = datetime.strptime(a.date, "%Y-%m-%d").date() if a.date else datetime.now(KST).date()
    start = end - timedelta(days=a.days - 1)
    lo = datetime(start.year, start.month, start.day, tzinfo=KST).timestamp()
    hi = datetime(end.year, end.month, end.day, tzinfo=KST).timestamp() + 86400

    files = sorted(glob.glob(os.path.join(a.log_dir, "access*.log*")))
    if not files:
        sys.exit(f"기록 파일이 없습니다: {a.log_dir}/access*.log")

    total = skipped_bad = 0
    excluded = Counter()
    views = Counter()  # 경로 -> 방문 수
    path_visitors = defaultdict(set)  # 경로 -> 방문자
    visitor_days = defaultdict(set)  # 방문자 -> 본 날짜
    app_visitors = set()
    watch = a.path
    watch_days = defaultdict(set)  # 방문자 -> 그 경로를 본 날짜
    day_views = Counter()
    day_visitors = defaultdict(set)

    for f in files:
        try:
            fh = open_log(f)
        except OSError as e:
            print(f"읽지 못함: {f} ({e})", file=sys.stderr)
            continue
        with fh:
            for line in fh:
                try:
                    r = json.loads(line)
                    ts = float(r["ts"])
                except (ValueError, KeyError, TypeError):
                    skipped_bad += 1
                    continue
                if not (lo <= ts < hi):
                    continue
                req = r.get("request") or {}
                total += 1
                headers = req.get("headers") or {}
                ua = header(headers, "User-Agent")
                path = (req.get("uri") or "").split("?", 1)[0] or "/"
                status = int(r.get("status") or 0)
                if req.get("method") != "GET" or status >= 400:
                    excluded["GET 아님·오류 응답"] += 1
                elif header(headers, "Next-Router-Prefetch"):
                    excluded["Next 미리 받기"] += 1
                elif not ua or BOT.search(ua):
                    excluded["봇·도구"] += 1
                elif NOT_PAGE.match(path):
                    excluded["페이지 아닌 경로"] += 1
                else:
                    ip = req.get("client_ip") or req.get("remote_ip") or ""
                    vid = hashlib.sha256(f"{ip}|{ua}".encode()).hexdigest()[:16]
                    day = datetime.fromtimestamp(ts, KST).date().isoformat()
                    seg = path if a.full else "/" + path.strip("/").split("/", 1)[0]
                    views[seg] += 1
                    path_visitors[seg].add(vid)
                    visitor_days[vid].add(day)
                    day_views[day] += 1
                    day_visitors[day].add(vid)
                    if "KORLeaguerApp" in ua:
                        app_visitors.add(vid)
                    if watch and (path == watch or path.startswith(watch.rstrip("/") + "/")):
                        watch_days[vid].add(day)

    counted = sum(views.values())
    visitors = len(visitor_days)
    print(f"접근 기록 집계 {start} ~ {end} (한국 시간, {a.days}일)")
    print(f"읽은 파일 {len(files)}개, 기간 안 요청 {total}줄" + (f", 깨진 줄 {skipped_bad}" if skipped_bad else ""))
    print()
    print("제외한 요청")
    for k, v in sorted(excluded.items(), key=lambda kv: -kv[1]):
        print("  " + pad(k, 22) + f"{v:>8}")
    print("  " + pad("센 방문(페이지 보기)", 22) + f"{counted:>8}")
    print()
    if not counted:
        print("센 방문이 없습니다.")
        return
    app = len(app_visitors)
    print(f"순방문자 {visitors}명 / 앱 {app}명({app / visitors:.0%}) / 웹 {visitors - app}명")
    if a.days > 1:
        back = sum(1 for d in visitor_days.values() if len(d) >= 2)
        print(f"재방문(서로 다른 날 2일 이상) {back}명({back / visitors:.0%})")
        print()
        print("날짜별")
        for day in sorted(day_views):
            print(f"  {day}  방문 {day_views[day]:>6}  순방문자 {len(day_visitors[day]):>5}")
    print()
    print(pad("경로", 34) + pad("방문", 8, True) + pad("순방문자", 10, True) + pad("비율", 7, True))
    for seg, n in views.most_common(a.top):
        pv = len(path_visitors[seg])
        print(f"{seg[:33]:<34}{n:>8}{pv:>10}{pv / visitors:>7.0%}")
    if len(views) > a.top:
        print(f"  … 그 밖 {len(views) - a.top}개 경로")
    if watch:
        wv = len(watch_days)
        print()
        print(f"{watch} 를 본 순방문자 {wv}명(전체의 {wv / visitors:.1%})")
        if wv:
            back = sum(1 for d in watch_days.values() if len(d) >= 2)
            print(f"  그중 서로 다른 날 2일 이상 본 사람 {back}명({back / wv:.0%})" + ("" if a.days > 1 else " — 하루치라 재방문은 --days로 기간을 늘려야 센다"))


if __name__ == "__main__":
    main()
