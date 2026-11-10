#!/usr/bin/env bash
# 서버에 관리용 계정을 만든다: SSH 키 로그인만, 비밀번호 없는 sudo.
# Claude Code가 서버 설정 바꾸기·장애 확인을 사람에게 비밀번호를 묻지 않고 직접 할 때 쓴다(2026-11-10 사용자 결정).
# 이 계정은 서버의 root 권한을 갖는다. 필요 없어지면 --remove로 지운다.
#
# 사용(서버에서 root로):
#   sudo bash add-admin.sh --user claude --key-file korleaguer-admin.pub   # 만들기(다시 실행하면 키만 바뀐다)
#   sudo bash add-admin.sh --user claude --remove                          # 지우기(계정·홈·sudo 권한)
set -euo pipefail

ADMIN_USER=""
KEY_FILE=""
REMOVE=0
while [ $# -gt 0 ]; do
  case "$1" in
    --user) ADMIN_USER="${2:-}"; shift 2 ;;
    --key-file) KEY_FILE="${2:-}"; shift 2 ;;
    --remove) REMOVE=1; shift ;;
    *) echo "모르는 옵션: $1" >&2; exit 1 ;;
  esac
done

[ "$(id -u)" = 0 ] || { echo "root(sudo)로 실행하세요" >&2; exit 1; }
[[ "$ADMIN_USER" =~ ^[a-z][a-z0-9_-]{1,31}$ ]] || { echo "--user에 소문자 계정 이름을 주세요(예: claude)" >&2; exit 1; }
case "$ADMIN_USER" in
  root | ubuntu | deploy) echo "기존 계정(${ADMIN_USER})은 쓰지 않습니다. 관리용 계정 이름을 따로 주세요" >&2; exit 1 ;;
esac
sudoers="/etc/sudoers.d/90-korleaguer-admin-${ADMIN_USER}"

if [ "$REMOVE" = 1 ]; then
  rm -f "$sudoers"
  if id "$ADMIN_USER" > /dev/null 2>&1; then
    pkill -u "$ADMIN_USER" 2> /dev/null || true
    userdel -r "$ADMIN_USER" 2> /dev/null || userdel "$ADMIN_USER"
  fi
  echo "관리용 계정 ${ADMIN_USER}를 지웠습니다"
  exit 0
fi

[ -s "$KEY_FILE" ] || { echo "--key-file에 SSH 공개키 파일을 주세요" >&2; exit 1; }
key="$(head -n 1 "$KEY_FILE")"
[[ "$key" =~ ^ssh-(ed25519|rsa)\ [A-Za-z0-9+/=]+(\ [^[:cntrl:]]*)?$ ]] || { echo "SSH 공개키 형식이 아닙니다" >&2; exit 1; }

# 1) 계정. 비밀번호로는 로그인할 수 없게 '*'로 둔다('!' 잠금은 일부 SSH 설정에서 키 로그인도 막는다).
if ! id "$ADMIN_USER" > /dev/null 2>&1; then
  useradd --create-home --shell /bin/bash "$ADMIN_USER"
fi
usermod -p '*' "$ADMIN_USER"
home="$(getent passwd "$ADMIN_USER" | cut -d: -f6)"

# 2) 이 키 하나만 받는다(다시 실행하면 바꾼다).
install -d -m 700 -o "$ADMIN_USER" -g "$ADMIN_USER" "$home/.ssh"
printf '%s\n' "$key" > "$home/.ssh/authorized_keys"
chown "$ADMIN_USER:$ADMIN_USER" "$home/.ssh/authorized_keys"
chmod 600 "$home/.ssh/authorized_keys"

# 3) 비밀번호 없는 sudo(문법을 먼저 확인하고 넣는다. 틀린 sudoers는 sudo 전체를 막는다).
tmp="$(mktemp)"
printf '%s ALL=(ALL) NOPASSWD:ALL\n' "$ADMIN_USER" > "$tmp"
chmod 440 "$tmp"
visudo -cf "$tmp" > /dev/null
mv "$tmp" "$sudoers"

# 4) SSH가 이 계정을 막지 않는지(접속 허용 목록이 있으면 알려 준다).
allow="$(sshd -T 2> /dev/null | grep -iE '^(allowusers|allowgroups) ' || true)"
if [ -n "$allow" ]; then
  echo "주의: sshd에 접속 허용 목록이 있습니다. ${ADMIN_USER}가 들어 있어야 접속됩니다:" >&2
  echo "$allow" >&2
fi

echo "관리용 계정 ${ADMIN_USER} 준비 끝(키 로그인, 비밀번호 없는 sudo). 키 지문: $(ssh-keygen -lf "$home/.ssh/authorized_keys" | cut -d' ' -f2)"
