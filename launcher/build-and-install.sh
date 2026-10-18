#!/usr/bin/env bash
# Build the launcher APK and install it on the TV as the default home screen.
#
# Some TVs (Google TV especially) have several apps that claim Home, and the stock one outranks ours, so
# `set-home-activity` alone reports Success while Home still opens the old launcher. After installing, this
# script lists every package that claims Home, asks before disabling them (restorable: tools/restore.sh), and
# repeats until Home resolves to this launcher, since disabling one lets the next in line take over.
#
# Usage: TV_HOST=192.168.1.50 launcher/build-and-install.sh
#   YES=1                 don't ask before disabling the packages that claim Home
#   STOCK_LAUNCHER=<pkg>  disable only this package in the first round (default: every other Home claimant)
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
STOCK_LAUNCHER="${STOCK_LAUNCHER:-}"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || echo /opt/homebrew/opt/openjdk@17)}"
export ANDROID_HOME="${ANDROID_HOME:-$([ -d ~/Library/Android/sdk ] && echo ~/Library/Android/sdk || echo /opt/homebrew/share/android-commandlinetools)}"
export PATH="$JAVA_HOME/bin:$PATH"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug -q --console=plain
PY=../tools/venv/bin/python
[ -x "$PY" ] || PY=../tools/venv/Scripts/python.exe
export MSYS_NO_PATHCONV=1
APK=app/build/outputs/apk/debug/app-debug.apk
ME=com.gathin.tvhome
DISABLED_LOG=home-claimants-disabled.txt   # read by tools/restore.sh to re-enable them

tv() { "$PY" -I ../tools/tv.py "$@"; }
# The system fallback home and Settings must never be disabled: the TV needs them.
home_claimants() {
  tv 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME' \
    | grep -oE '^ +[A-Za-z0-9_.]+/' | tr -d ' /' \
    | grep -vxE "$ME|android|com.android.tv.settings|com.android.settings" || true
}
current_home() {
  tv 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' \
    | tail -n 1 | tr -d '\r'
}
confirm() {
  [ "${YES:-}" = 1 ] && return 0
  if [ -t 0 ]; then
    read -r -p "Disable them? Undo with tools/restore.sh [y/N] " ans
    [[ "$ans" == [yY]* ]] && return 0
    echo "Skipped. The launcher is installed but Home still opens $(current_home)."; exit 1
  fi
  echo "Not interactive: re-run with YES=1 to disable them. The launcher is installed but not yet the Home screen."
  exit 1
}

"$PY" -I ../tools/b64push.py "$APK" /data/local/tmp/tvhome.apk
out=$(tv 'pm install -r /data/local/tmp/tvhome.apk; rm -f /data/local/tmp/tvhome.apk')
echo "$out"
case "$out" in *Success*) ;; *) echo "pm install failed; nothing else was touched."; exit 1;; esac
tv "pm grant $ME android.permission.ACCESS_FINE_LOCATION 2>/dev/null; echo location-granted"
set_home() { tv "cmd package set-home-activity --user 0 $ME/.MainActivity"; }
set_home

for round in 1 2 3 4 5; do
  # Round 1 always runs: even when we already win, the stock launcher should not come back after an update.
  if [ "$round" -gt 1 ] && [ "$(current_home | cut -d/ -f1)" = "$ME" ]; then break; fi
  if [ "$round" -eq 1 ] && [ -n "$STOCK_LAUNCHER" ]; then list="$STOCK_LAUNCHER"; else list=$(home_claimants); fi
  if [ -z "$list" ]; then
    [ "$(current_home | cut -d/ -f1)" = "$ME" ] && break
    echo "No other app claims Home, yet Home resolves to $(current_home). Check 'cmd package query-activities' on the TV."
    exit 1
  fi
  echo "These packages claim Home and outrank, or could replace, the new launcher:"
  echo "$list" | sed 's/^/  /'
  confirm
  for p in $list; do
    tv "pm disable-user --user 0 $p"
    grep -qxF "$p" "$DISABLED_LOG" 2>/dev/null || echo "$p" >> "$DISABLED_LOG"
  done
  set_home
done

now=$(current_home)
if [ "${now%%/*}" != "$ME" ]; then
  echo "Home still resolves to $now. Re-enable with tools/restore.sh and check which package claims Home."
  exit 1
fi
echo "Installed. Home now opens $now. Press Home on the remote to see it."
