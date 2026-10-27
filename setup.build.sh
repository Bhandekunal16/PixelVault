#!/bin/bash

compiler="./gradlew"
command=$(emulator -list-avds | head -n 1)

build() {
    # adb -e uninstall com.example.generalExpanseTracker &&
    $compiler clean &&
    $compiler build --stacktrace &&
    $compiler assembleDebug &&
    $compiler installDebug
}

if [ "$1" = "build" ]; then
    build

elif [ "$1" = "startup" ]; then
    gradle wrapper

elif [ "$1" = "run" ]; then 
    emulator -avd "$command"

else
    echo "unknown command"
fi