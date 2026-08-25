#!/bin/bash
# Run a matrix row for a call the MAINTAINER places (VoIP — WhatsApp and friends cannot be dialled
# by adb the way a carrier call can).
#
# Waits for the audio mode to enter MODE_IN_COMMUNICATION, then drives the near-side clip exactly as
# the carrier runner does, waits for the call to end, and reports.
#   ./watchrow.sh <row-id> [wait-seconds]
set -u
cd "$(dirname "$0")" || exit 1
S=${S:-daabf34f}
CLIP=/sdcard/Music/nearside.mp3
PLAYER=com.heytap.browser/.core.component.video.StandardVideoActivity
REC_DIR=/sdcard/OP9Recordings
OUT=./pulled
ROW=${1:-S?}
WAIT=${2:-90}
a() { adb -s "$S" "$@"; }

mode()     { a shell dumpsys audio 2>/dev/null | LC_ALL=C grep -m1 'Actual mode' | grep -oE 'MODE_[A-Z_]+'; }
services() { a shell dumpsys activity services com.baba.callvault 2>/dev/null | grep -oE 'ServiceRecord\{[^}]*\}' | sed 's/.*\///' | sort -u | tr '\n' ' '; }
wd()       { a shell settings get global adb_wifi_enabled | tr -d '\r'; }
recs()     { a shell "ls $REC_DIR 2>/dev/null | wc -l" | tr -d '\r'; }

toggle_speaker() {
  a shell uiautomator dump /sdcard/c.xml >/dev/null 2>&1
  local xy
  xy=$(a shell cat /sdcard/c.xml 2>/dev/null | python3 ./uinodes.py tapdesc "speaker")
  if [ -n "$xy" ]; then a shell input tap $xy; sleep 2; echo "   speaker toggled ($1)"
  else echo "   !! no speaker control found ($1) — put the call on speaker by hand"; fi
}

echo "############ ROW $ROW ############"
echo "before: recordings=$(recs) wd=$(wd) mode=$(mode)"
a logcat -c
echo
echo ">>> PLACE THE CALL NOW (waiting up to ${WAIT}s) <<<"
for i in $(seq 1 "$WAIT"); do
  M=$(mode)
  [ "$M" = "MODE_IN_COMMUNICATION" ] && { echo "   call detected after ${i}s ($M)"; break; }
  printf "."
  sleep 1
done
[ "$(mode)" != "MODE_IN_COMMUNICATION" ] && { echo; echo "   no VoIP call detected; giving up"; exit 1; }

sleep 3
echo "== mid-call =="
echo "   services: $(services)"
echo "   wd      : $(wd)"

echo "== speakerphone + near-side clip =="
toggle_speaker on
a shell "am start -n $PLAYER -a android.intent.action.VIEW -d file://$CLIP -t audio/mpeg" >/dev/null 2>&1
sleep 6
a shell am force-stop com.heytap.browser >/dev/null 2>&1
toggle_speaker off

echo "   SPEAK NOW, then hang up when you are done"
for i in $(seq 1 120); do
  [ "$(mode)" != "MODE_IN_COMMUNICATION" ] && { echo "   call ended after ${i}s"; break; }
  sleep 1
done
sleep 8

echo
echo "== after =="
echo "   recordings: $(recs)   services: $(services)   wd: $(wd)"
echo
echo "== log =="
a logcat -d -v time 2>/dev/null | LC_ALL=C grep 'CV:' \
  | LC_ALL=C grep -iE 'voip|arm|handoff|direct|scrcpy|speaker turns|Recording (started|stopped)|session|policy' \
  | tail -22 | sed 's/^/   /'

echo
echo "== the file =="
mkdir -p "$OUT"
NEW=$(a shell "ls -t $REC_DIR 2>/dev/null | head -1" | tr -d '\r')
echo "   newest: $NEW"
a pull "$REC_DIR/$NEW" "$OUT/" >/dev/null 2>&1 && {
  F="$OUT/$NEW"
  echo "   size: $(stat -f%z "$F") bytes"
  ffprobe -v error -show_entries format=duration:stream=channels,codec_name -of default=nw=1 "$F" | sed 's/^/   /'
  ffmpeg -i "$F" -af volumedetect -f null - 2>&1 | grep -oE 'mean_volume: [-0-9.]+ dB|max_volume: [-0-9.]+ dB' | sed 's/^/   /'
  CH=$(ffprobe -v error -show_entries stream=channels -of default=nw=1:nk=1 "$F" | head -1)
  if [ "$CH" = "2" ]; then
    for c in 0 1; do
      printf "   channel %s: " "$c"
      ffmpeg -i "$F" -af "pan=mono|c0=c$c,volumedetect" -f null - 2>&1 | grep -oE 'mean_volume: [-0-9.]+ dB' | head -1
    done
  fi
  echo "   -- silence spans --"
  ffmpeg -i "$F" -af silencedetect=noise=-35dB:d=1.5 -f null - 2>&1 | grep -oE 'silence_(start|end): [0-9.]+' | head -8 | sed 's/^/   /'
}
