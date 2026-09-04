#!/bin/sh

adb connect 192.168.204.252:37001

#TEL=$(adb  devices | awk '/device$/ && !/emulator/{print $1; exit}')
#echo $TEL
#adb connect $TEL
