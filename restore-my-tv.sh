#!/usr/bin/env bash
# Undo everything done to the Xiaomi MiTV-MZTU0: re-enable every debloated package, the Google TV setup
# wizard (setupwraith, disabled so it would stop claiming Home) and the stock Google TV launcher.
set -euo pipefail
cd "$(dirname "$0")"
export TV_HOST="${TV_HOST:-192.168.1.14}"
echo "pm enable com.google.android.tungsten.setupwraith" | tools/venv/bin/python -I tools/tv.py --batch
tools/restore.sh examples/xiaomi-mitv-mztu0/debloat.txt com.google.android.apps.tv.launcherx
echo "Restored. To also remove the new launcher: tools/venv/bin/python -I tools/tv.py 'pm uninstall com.gathin.tvhome'"
