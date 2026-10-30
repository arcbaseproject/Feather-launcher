#!/usr/bin/env bash
# Measures a launcher's memory, startup and idle CPU over adb.
# Usage: ./benchmark.sh <package> [samples] [settle_seconds]
#   ./benchmark.sh dev.feather.launcher 5
#   ./benchmark.sh com.android.launcher3 5     # LineageOS Trebuchet
# Makes <package> the default Home app for the run. It does not restore the previous one:
#   adb shell cmd role add-role-holder android.app.role.HOME com.android.launcher3
set -euo pipefail

PKG=${1:?usage: $0 <package> [samples] [settle_seconds]}
N=${2:-5}
SETTLE=${3:-10}
ADB=${ADB:-adb}
sh() { $ADB shell "$@" | tr -d '\r'; }

sh cmd role add-role-holder android.app.role.HOME "$PKG" >/dev/null
COMP=$(sh cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME "$PKG" | tail -1)
read -r W H < <(sh wm size | awk -F'[ x]' '/Physical/{print $3, $4}')

stats() { sort -n | awk '{a[NR]=$1} END {m=(NR%2)?a[(NR+1)/2]:(a[NR/2]+a[NR/2+1])/2; printf "median %8.1f   min %8.1f   max %8.1f   (n=%d)\n", m, a[1], a[NR], NR}'; }

# Prints: total_pss total_rss java native graphics swap (KiB)
meminfo() {
    sh dumpsys meminfo "$PKG" | awk '
        /Java Heap:/   {j=$3} /Native Heap:/ {n=$3} /Graphics:/ {g=$2}
        /TOTAL PSS:/   {p=$3; r=$6; s=$NF}
        END {print p, r, j, n, g, s}'
}

sample_set() { # $1 = label, $2 = 1 to open and close the drawer before each sample
    : >"$TMP/$1"
    for _ in $(seq "$N"); do
        sh input keyevent KEYCODE_HOME
        if [ "$2" = 1 ]; then
            sleep 1
            sh input swipe $((W / 2)) $((H * 4 / 5)) $((W / 2)) $((H / 3)) 150
            sleep 2
            sh input keyevent KEYCODE_HOME
        fi
        sleep "$SETTLE"
        meminfo >>"$TMP/$1"
    done
}

report() { # $1 = label
    echo "== $1 (KiB)"
    local i=1
    for name in "Total PSS" "Total RSS" "Java heap" "Native heap" "Graphics" "Swap"; do
        printf "  %-12s " "$name"; awk -v c=$i '{print $c}' "$TMP/$1" | stats
        i=$((i + 1))
    done
}

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

echo "package: $PKG   component: $COMP   device: $(sh getprop ro.product.device) / Android $(sh getprop ro.build.version.release)"
echo "apps: $(sh cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER | grep -c /)"

# Warm-up so first-run work (dex verification, icon loading) is not measured.
sh input keyevent KEYCODE_HOME
sleep "$SETTLE"

sample_set home 0
sample_set drawer 1
report home
report drawer

: >"$TMP/cold"; : >"$TMP/warm"
for _ in $(seq "$N"); do
    # The system relaunches the default home right after force-stop, so read its "Displayed" log line.
    sh logcat -c
    sh am force-stop "$PKG"
    sleep 5
    sh logcat -d -s ActivityTaskManager | grep -m1 "Displayed $COMP" | grep -oE '\+([0-9]+s)?[0-9]+ms' | tr -d '+' |
        awk -F's' '{ print (NF == 3) ? $1 * 1000 + $2 : $1 + 0 }' >>"$TMP/cold"
    sh am start -W -a android.settings.SETTINGS >/dev/null
    sleep 2
    sh am start -W -n "$COMP" | awk '/TotalTime/{print $2}' >>"$TMP/warm"
    sleep 2
done
printf "== Cold start ms   "; stats <"$TMP/cold"
printf "== Warm start ms   "; stats <"$TMP/warm"

# Idle CPU: utime+stime ticks over 30 s while sitting on the home screen.
sh input keyevent KEYCODE_HOME
sleep "$SETTLE"
PID=$(sh pidof "$PKG")
ticks() { sh cat "/proc/$PID/stat" | awk '{print $14 + $15}'; }
T0=$(ticks); sleep 30; T1=$(ticks)
HZ=$(sh getconf CLK_TCK 2>/dev/null || echo 100)
echo "== Idle CPU over 30 s: $((T1 - T0)) ticks ($(awk -v t=$((T1 - T0)) -v hz="$HZ" 'BEGIN{printf "%.2f", t / hz / 30 * 100}')% of one core)"

echo "== Resident launchers (both resident means both count against RAM):"
for p in dev.feather.launcher com.android.launcher3; do echo "  $p: pid $(sh pidof $p || echo none)"; done
