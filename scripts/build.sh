#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if [ -z "${JAVA_HOME:-}" ] && [ -d '/Applications/Android Studio.app/Contents/jbr/Contents/Home' ]; then
 export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
fi
if [ -z "${ANDROID_HOME:-}" ] && [ -d "$HOME/Library/Android/sdk" ]; then
 export ANDROID_HOME="$HOME/Library/Android/sdk"
fi
exec ./gradlew "${@:-assembleRelease}" --project-cache-dir "$HOME/.cache/weather/gradle-project" --console=plain --max-workers=3
