#!/usr/bin/env bash
# Screenshots the running fff3 window: tools/screenshot.sh out.png
set -euo pipefail
id="$(swift -e '
import CoreGraphics
let windows = CGWindowListCopyWindowInfo([.optionOnScreenOnly], kCGNullWindowID) as! [[String: Any]]
let ours = windows.first { ($0[kCGWindowOwnerName as String] as? String) == "fff3" && ($0[kCGWindowLayer as String] as? Int) == 0 }
print(ours?[kCGWindowNumber as String] as? Int ?? 0)
')"
if [ "$id" = "0" ]; then echo "no fff3 window on screen" >&2; exit 1; fi
screencapture -x -o -l "$id" "$1"
echo "$1"
