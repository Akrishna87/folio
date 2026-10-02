#!/usr/bin/env bash
# Runs inside the Android emulator job against the real catalogs: installs the APK, searches
# for a book, reads the ebook (checking pages turn), streams the audiobook (checking it keeps
# playing in the background) and checks both end up on the shelf.
# Usage: smoke-test.sh <apk> <output dir>
set -euo pipefail

APK="$1"
OUT="$2"
PKG=io.github.akrishna87.mybooks
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$OUT"

fail() {
  echo "SMOKE TEST FAILED: $*"
  adb exec-out screencap -p > "$OUT/failure.png" || true
  if adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/failure.xml" > /dev/null 2>&1; then
    echo "On screen:"; grep -o 'text="[^"]\+"' "$OUT/failure.xml" | head -60 || true
  fi
  adb logcat -d > "$OUT/logcat.txt" || true
  exit 1
}
dump() {
  local i
  for i in 1 2 3; do
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/$1.xml" > /dev/null 2>&1 || { sleep 2; continue; }
    # The emulator's own apps sometimes freeze while it warms up; wave the "isn't responding"
    # popup away so it doesn't cover the app. Crashes of My Books itself are caught from logcat.
    grep -q "isn&apos;t responding\|isn't responding" "$OUT/$1.xml" || return 0
    echo "(dismissing a system 'isn't responding' popup)"
    python3 "$HERE/find_text.py" "$OUT/$1.xml" "Wait" > /dev/null 2>&1 && adb shell input tap $(python3 "$HERE/find_text.py" "$OUT/$1.xml" "Wait")
    sleep 3
  done
}
shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
tap() { # tap <dump name> <text> [first|last]
  local xy
  xy=$(python3 "$HERE/find_text.py" "$OUT/$1.xml" "$2" "${3:-first}") || fail "couldn't find '$2' on screen"
  adb shell input tap $xy
}
wait_for() { # wait_for <dump name> <grep -E pattern> <seconds> <what>
  local end=$((SECONDS + $3))
  while [ $SECONDS -lt $end ]; do
    dump "$1"
    grep -Eq "$2" "$OUT/$1.xml" && return 0
    sleep 3
  done
  fail "$4 (waited $3 s)"
}
session() { adb shell dumpsys media_session > "$OUT/session.txt"; }
playing() { session; grep -Eq "\{state=(PLAYING|3)" "$OUT/session.txt"; }
screen_size() { adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1; }
hide_keyboard() { if adb shell dumpsys input_method | grep -q "mInputShown=true"; then adb shell input keyevent KEYCODE_BACK; sleep 1; fi; }

adb wait-for-device
adb install -r "$APK"
adb shell settings put global hide_error_dialogs 1 || true
adb logcat -c

SIZE=$(screen_size); W=${SIZE%x*}; H=${SIZE#*x}

echo "--- Launching the app (opens on Home)"
adb shell am start -W -n "$PKG/.MainActivity"
wait_for home 'text="Popular audiobooks"' 30 "Home doesn't show 'Popular audiobooks'"
sleep 15 # let the catalogs answer
dump home
shot 1-home
grep -q 'text="Popular ebooks"' "$OUT/home.xml" || fail "Home doesn't show 'Popular ebooks'"
grep -q "Couldn't reach\|No internet" "$OUT/home.xml" && echo "WARNING: a catalog didn't answer on Home"
echo "PASS: Home screen"

echo "--- Searching"
tap home "Search"
sleep 2
dump search
grep -q 'text="Browse by subject"' "$OUT/search.xml" || fail "Search doesn't show the subject tiles"
tap search "Title or author"
sleep 1
adb shell input text "pride%sand%sprejudice"
adb shell input keyevent KEYCODE_ENTER
sleep 3
hide_keyboard
wait_for results 'text="Pride and Prejudice"' 90 "searching didn't find Pride and Prejudice"
# Both catalogs should have answered.
wait_for results 'text="Ebooks"' 10 "no ebook section"
for _ in $(seq 1 20); do
  dump results
  grep -q 'content-desc="Audiobook"\|text="Audiobook"' "$OUT/results.xml" && grep -q 'text="Ebook"' "$OUT/results.xml" && break
  sleep 3
done
shot 2-search-results
grep -q 'text="Audiobook"' "$OUT/results.xml" || fail "no audiobook results"
grep -q 'text="Ebook"' "$OUT/results.xml" || fail "no ebook results"
echo "PASS: search finds audiobooks and ebooks"

echo "--- Reading the ebook"
tap results "Ebooks"
sleep 2
wait_for ebooks 'text="Pride and Prejudice"' 30 "the Ebooks filter lost the results"
tap ebooks "Pride and Prejudice"
wait_for ebook-page 'text="Read"' 20 "the ebook page has no Read button"
shot 3-ebook-page
tap ebook-page "Read"
wait_for reader 'text="Page 1 of [0-9]+"' 90 "the reader didn't open the book at page 1"
sleep 2
shot 4-reader-first
# Turn pages by tapping the right side of the screen.
for _ in $(seq 1 6); do adb shell input tap $((W * 9 / 10)) $((H / 2)); sleep 2; done
shot 5-reader-turned
# Tap the middle for the menu, which says which part of the book this is.
adb shell input tap $((W / 2)) $((H / 2))
sleep 2
dump reader-menu
shot 6-reader-menu
grep -q 'of the book' "$OUT/reader-menu.xml" || fail "tapping the middle didn't show the reader's menu"
grep -Eq 'text="Part ([2-9]|[1-9][0-9]+) of [0-9]+"' "$OUT/reader-menu.xml" || grep -Eq 'text="Page ([2-9]|[1-9][0-9]+) of' "$OUT/reader-menu.xml" \
  || fail "tapping the right side didn't turn the page"
echo "PASS: the ebook opens and pages turn"
tap reader-menu "Text and colours"
sleep 1
dump style
tap style "Sepia"
sleep 2
shot 7-reader-sepia
adb shell input keyevent KEYCODE_BACK # close the panel
sleep 1
adb shell input keyevent KEYCODE_BACK # close the book
sleep 2
wait_for ebook-again 'text="Continue reading"' 15 "the ebook page doesn't offer 'Continue reading' after reading"
echo "PASS: reading progress is remembered"

echo "--- Playing the audiobook"
adb shell input keyevent KEYCODE_BACK # back to the results
sleep 2
dump results-again
tap results-again "Audiobooks"
sleep 2
wait_for audiobooks 'text="Pride and Prejudice"' 30 "the Audiobooks filter has no Pride and Prejudice"
tap audiobooks "Pride and Prejudice"
wait_for audio-page 'text="Parts"' 60 "the audiobook's parts didn't load"
shot 8-audiobook-page
grep -Eq 'text="Download · [0-9.,]+ [MG]B"' "$OUT/audio-page.xml" || fail "the Download button doesn't show the size"
tap audio-page "Play"
for _ in $(seq 1 30); do playing && break; sleep 2; done
playing || fail "the audiobook didn't start playing"
echo "PASS: the audiobook streams"
dump player
shot 9-player
grep -q 'LISTENING TO' "$OUT/player.xml" || fail "the full player didn't open"
tap player "1.0×"
sleep 1
dump speeds
tap speeds "1.5×"
sleep 2
dump player-fast
grep -q 'text="1.5×"' "$OUT/player-fast.xml" || fail "the speed didn't change to 1.5×"
echo "PASS: playback speed"

echo "--- Background playback"
adb shell input keyevent KEYCODE_HOME
sleep 8
playing || fail "playback stopped when the app went to the background"
echo "PASS: keeps playing in the background"
adb shell cmd statusbar expand-notifications
sleep 2
shot 10-notification
adb shell cmd statusbar collapse

echo "--- Shelf"
adb shell am start -W -n "$PKG/.MainActivity"
sleep 2
adb shell input keyevent KEYCODE_BACK # close the full player
sleep 1
dump before-shelf
tap before-shelf "Shelf"
sleep 2
dump shelf
shot 11-shelf
[ "$(grep -o 'text="Pride and Prejudice"' "$OUT/shelf.xml" | wc -l)" -ge 2 ] || fail "the shelf doesn't list both books"
echo "PASS: both books are on the shelf"
tap shelf "Home"
sleep 2
dump home-after
shot 12-home-after
grep -q 'text="Continue"' "$OUT/home-after.xml" || fail "Home doesn't show 'Continue'"
echo "PASS: Home offers to continue"

if adb logcat -d | grep -q "FATAL EXCEPTION"; then
  adb logcat -d > "$OUT/logcat.txt"
  fail "the app crashed (see logcat.txt)"
fi
echo "ALL SMOKE TESTS PASSED"
