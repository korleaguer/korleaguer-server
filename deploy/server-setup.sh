#!/usr/bin/env bash
# 코리거 서버 준비 스크립트. 서버(Debian·Ubuntu 계열)에서 한 번 sudo로 실행한다. 다시 실행해도 안전하다.
#
#   sudo ./server-setup.sh \
#     --server-key "ssh-ed25519 AAAA... gha-korleaguer-server" \
#     --web-key "ssh-ed25519 AAAA... gha-korleaguer-web" \
#     [--site pigeon.ddns.net] [--ssh-host pigeon.ddns.net] [--ssh-port 22] [--harden-ssh]
#
# 하는 일
#  1) Docker Engine·compose 플러그인 설치(없을 때만, Docker 공식 apt 저장소)
#  2) 배포 전용 계정 deploy(비밀번호 없음, sudo 없음, docker 그룹)
#  3) /srv/korleaguer에 compose.yaml·Caddyfile 복사, .env·api.env·mysql.env 만들기
#     (DB 비밀번호·관리용 토큰은 임의로 만든다. 이미 있으면 건드리지 않는다)
#  4) /usr/local/bin/korleaguer-deploy·korleaguer-backup 설치, 매일 04:30 DB 백업(cron)
#  5) deploy 계정 authorized_keys: 키마다 "배포 명령 하나만" 실행되게 제한
#  6) GitHub variable DEPLOY_KNOWN_HOSTS에 넣을 한 줄과 서버 키 지문 출력
#  --harden-ssh: 비밀번호 로그인과 root 로그인을 끈다. 지금 쓰는 계정이 SSH 키로 로그인되는지 먼저 확인할 것.
set -euo pipefail

SERVER_KEY=""
WEB_KEY=""
SITE="pigeon.ddns.net"
SSH_HOST="pigeon.ddns.net"
SSH_PORT="22"
HARDEN_SSH=0
DEPLOY_USER="deploy"
DEPLOY_DIR="/srv/korleaguer"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

while [ $# -gt 0 ]; do
  case "$1" in
    --server-key) SERVER_KEY="$2"; shift 2 ;;
    --web-key) WEB_KEY="$2"; shift 2 ;;
    --site) SITE="$2"; shift 2 ;;
    --ssh-host) SSH_HOST="$2"; shift 2 ;;
    --ssh-port) SSH_PORT="$2"; shift 2 ;;
    --harden-ssh) HARDEN_SSH=1; shift ;;
    *) echo "모르는 옵션: $1" >&2; exit 1 ;;
  esac
done

say() { printf '\n== %s\n' "$*"; }
[ "$(id -u)" -eq 0 ] || { echo "sudo로 실행하세요" >&2; exit 1; }
for f in compose.yaml Caddyfile korleaguer-deploy korleaguer-backup; do
  [ -f "$HERE/$f" ] || { echo "$HERE/$f가 없습니다. deploy 폴더 통째로 서버에 복사한 뒤 실행하세요" >&2; exit 1; }
done
valid_key() { [[ "$1" =~ ^ssh-ed25519\ [A-Za-z0-9+/=]+(\ .*)?$ ]]; }
for k in "$SERVER_KEY" "$WEB_KEY"; do
  [ -z "$k" ] || valid_key "$k" || { echo "ed25519 공개키 형식이 아닙니다: $k" >&2; exit 1; }
done

# 1) Docker
if ! command -v docker > /dev/null || ! docker compose version > /dev/null 2>&1; then
  say "Docker 설치"
  . /etc/os-release
  case "${ID:-}" in
    ubuntu | debian) distro="$ID" ;;
    *) if [[ "${ID_LIKE:-}" == *debian* ]]; then distro=debian; else
         echo "Debian·Ubuntu 계열이 아닙니다. Docker Engine과 compose 플러그인을 직접 설치한 뒤 다시 실행하세요" >&2; exit 1
       fi ;;
  esac
  apt-get update
  apt-get install -y ca-certificates curl
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL "https://download.docker.com/linux/${distro}/gpg" -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/${distro} ${VERSION_CODENAME} stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update
  apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  systemctl enable --now docker
fi
command -v crontab > /dev/null || { apt-get update && apt-get install -y cron && systemctl enable --now cron; }
command -v flock > /dev/null || apt-get install -y util-linux

# 2) 배포 계정
if ! id "$DEPLOY_USER" > /dev/null 2>&1; then
  say "배포 계정 ${DEPLOY_USER} 만들기"
  useradd --create-home --shell /bin/bash "$DEPLOY_USER"
  passwd -l "$DEPLOY_USER" > /dev/null
fi
usermod -aG docker "$DEPLOY_USER"

# 3) 운영 폴더
say "${DEPLOY_DIR} 준비"
install -d -m 750 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "$DEPLOY_DIR" "$DEPLOY_DIR/backups"
install -m 640 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "$HERE/compose.yaml" "$DEPLOY_DIR/compose.yaml"
install -m 640 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "$HERE/Caddyfile" "$DEPLOY_DIR/Caddyfile"
random() { openssl rand -hex 24; }
make_secret_file() {
  local file="$1"; shift
  if [ ! -f "$file" ]; then
    printf '%s\n' "$@" > "$file"
    echo "만듦: $file"
  else
    echo "있음(그대로 둠): $file"
  fi
  chown "$DEPLOY_USER:$DEPLOY_USER" "$file"
  chmod 600 "$file"
}
db_password="$(random)"
make_secret_file "$DEPLOY_DIR/mysql.env" \
  "MYSQL_DATABASE=korleaguer" "MYSQL_USER=korleaguer" "MYSQL_PASSWORD=${db_password}" "MYSQL_ROOT_PASSWORD=$(random)"
# api.env를 처음 만들 때만 mysql.env와 같은 비밀번호를 쓴다(이미 있으면 둘 다 그대로).
db_password="$(grep '^MYSQL_PASSWORD=' "$DEPLOY_DIR/mysql.env" | cut -d= -f2-)"
make_secret_file "$DEPLOY_DIR/api.env" \
  "SPRING_DATASOURCE_PASSWORD=${db_password}" "KORLEAGUER_DATA_SOURCE=mlb" "KORLEAGUER_ADMIN_TOKEN=$(random)"
if [ ! -f "$DEPLOY_DIR/.env" ]; then
  printf 'SITE_ADDRESS=%s\n' "$SITE" > "$DEPLOY_DIR/.env"
fi
chown "$DEPLOY_USER:$DEPLOY_USER" "$DEPLOY_DIR/.env"
chmod 640 "$DEPLOY_DIR/.env"

# 4) 스크립트·백업
install -m 755 "$HERE/korleaguer-deploy" /usr/local/bin/korleaguer-deploy
install -m 755 "$HERE/korleaguer-backup" /usr/local/bin/korleaguer-backup
cat > /etc/cron.d/korleaguer-backup <<EOF
# 코리거 DB 매일 백업(매일 백업 14개, 배포 전·수동 백업 5개를 따로 보관)
30 4 * * * ${DEPLOY_USER} /usr/local/bin/korleaguer-backup >> ${DEPLOY_DIR}/backups/backup.log 2>&1
EOF
chmod 644 /etc/cron.d/korleaguer-backup

# 5) 배포 키: 키마다 배포 명령 하나만
ssh_dir="/home/${DEPLOY_USER}/.ssh"
install -d -m 700 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "$ssh_dir"
keys_file="$ssh_dir/authorized_keys"
touch "$keys_file"
add_key() {
  local service="$1" key="$2" tmp
  [ -n "$key" ] || return 0
  tmp="$(mktemp)"
  grep -v "korleaguer-deploy ${service}\"" "$keys_file" > "$tmp" || true
  printf 'restrict,command="/usr/local/bin/korleaguer-deploy %s" %s\n' "$service" "$key" >> "$tmp"
  mv "$tmp" "$keys_file"
  echo "배포 키 등록: ${service}"
}
add_key api "$SERVER_KEY"
add_key web "$WEB_KEY"
chown "$DEPLOY_USER:$DEPLOY_USER" "$keys_file"
chmod 600 "$keys_file"

# SSH 비밀번호·root 로그인 끄기(선택)
if [ "$HARDEN_SSH" = 1 ]; then
  say "SSH: 비밀번호 로그인·root 로그인 끄기"
  install -d /etc/ssh/sshd_config.d
  # sshd는 같은 설정이 여러 번 나오면 처음 값을 쓴다. 다른 파일(예: Ubuntu의 50-cloud-init.conf)보다 먼저 읽히게 00-으로 둔다.
  rm -f /etc/ssh/sshd_config.d/90-korleaguer.conf
  cat > /etc/ssh/sshd_config.d/00-korleaguer.conf <<'EOF'
# 코리거 서버: SSH는 키로만 로그인한다
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin no
EOF
  grep -qE '^[[:space:]]*Include[[:space:]]+/etc/ssh/sshd_config.d/\*\.conf' /etc/ssh/sshd_config \
    || echo "주의: /etc/ssh/sshd_config에 sshd_config.d를 읽는 Include 줄이 없습니다. 직접 확인하세요" >&2
  sshd -t
  systemctl reload ssh 2> /dev/null || systemctl reload sshd
  # 실제로 적용된 값을 확인한다(다른 설정이 이기면 멈춘다)
  effective="$(sshd -T 2> /dev/null | grep -E '^(passwordauthentication|kbdinteractiveauthentication|permitrootlogin) ')"
  echo "$effective"
  if echo "$effective" | grep -qvE ' no$'; then
    echo "SSH 설정이 기대와 다릅니다(위 값 중 no가 아닌 것). /etc/ssh/sshd_config와 sshd_config.d를 확인하세요" >&2
    exit 1
  fi
fi

# 6) GitHub에 넣을 값
host_key="$(cut -d' ' -f1,2 /etc/ssh/ssh_host_ed25519_key.pub)"
if [ "$SSH_PORT" = 22 ]; then known_host="${SSH_HOST} ${host_key}"; else known_host="[${SSH_HOST}]:${SSH_PORT} ${host_key}"; fi
say "끝. GitHub 두 저장소(korleaguer-server, korleaguer-web)의 Actions variable에 넣을 값"
echo "DEPLOY_HOST=${SSH_HOST}"
[ "$SSH_PORT" = 22 ] || echo "DEPLOY_PORT=${SSH_PORT}"
echo "DEPLOY_PLATFORM=linux/$(dpkg --print-architecture | sed 's/armhf/arm\/v7/')"
echo "DEPLOY_KNOWN_HOSTS=${known_host}"
echo
echo "서버 키 지문(처음 접속할 때 보이는 값과 같은지 확인): $(ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub)"
echo "관리용 토큰·DB 비밀번호: ${DEPLOY_DIR}/api.env, ${DEPLOY_DIR}/mysql.env (deploy 계정만 읽을 수 있음)"
