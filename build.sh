#!/bin/bash
# KageCam build helper for this VM (see AGENTS.md "Android build in this VM").
# - Java TLS through the egress proxy gets connection-reset, and Java's IPv6
#   loopback is intercepted: use the local curl-backed mirror (tools/mirror.py)
#   and force IPv4. Gradle daemon TCP is also broken here: run in-process.
set -e
cd "$(dirname "$0")"
export JAVA_HOME=~/workspace/tools/jdk21
export GRADLE_USER_HOME=~/workspace/tools/gradle-home
export GRADLE_OPTS="-Xmx768m -Dfile.encoding=UTF-8 -Djava.net.preferIPv4Stack=true"
# start mirror if not listening
if ! curl -s -o /dev/null --max-time 3 http://127.0.0.1:18080/central/; then
  (cd ~/workspace/tools && nohup python3 mirror.py > /tmp/mirror.log 2>&1 &)
  sleep 1
fi
# NOTE: gradle-8.14.3 forks a single-use daemon whose direct TCP is intercepted
# by the runtime permission layer (other_tcp Deny) -> plugin resolution fails.
# Gradle 8.9 runs the build without that problem. Keep 8.9 here.
# ビルド直列化ルール（2026-11-23 全プロジェクト共通）：同時ビルドはVM全体で
# 2本まで。共通ラッパー経由で実行し、空き枠を待ってから走らせる。
exec ~/workspace/tools/with-build-lock.sh ~/workspace/tools/gradle-8.9/bin/gradle "$@" \
  -Dorg.gradle.daemon=false -Dkotlin.daemon.enabled=false --max-workers=1
