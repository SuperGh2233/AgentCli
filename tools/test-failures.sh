#!/usr/bin/env bash
# 从 Maven Surefire 报告里列出失败和报错的测试（类名#方法名），排好序输出。
# 用法：
#   mvn test -Pquick; tools/test-failures.sh > /tmp/before.txt
#   （改代码后）mvn test -Pquick; tools/test-failures.sh > /tmp/after.txt
#   diff /tmp/before.txt /tmp/after.txt      # 多出来的行就是新引入的失败
# 可选参数：报告目录，默认 target/surefire-reports
set -euo pipefail

dir="${1:-target/surefire-reports}"
if [ ! -d "$dir" ]; then
  echo "找不到报告目录: $dir（先跑一次 mvn test）" >&2
  exit 1
fi

shopt -s nullglob
reports=("$dir"/TEST-*.xml)
if [ ${#reports[@]} -eq 0 ]; then
  echo "报告目录里没有 TEST-*.xml: $dir" >&2
  exit 1
fi

awk '
  /<testcase / {
    name = ""; cls = "";
    if (match($0, /name="[^"]*"/))      name = substr($0, RSTART + 6, RLENGTH - 7);
    if (match($0, /classname="[^"]*"/)) cls  = substr($0, RSTART + 11, RLENGTH - 12);
    current = cls "#" name;
    if ($0 ~ /\/>[[:space:]]*$/) current = "";
  }
  /<failure|<error/ { if (current != "") { print current; current = "" } }
  /<\/testcase>/ { current = "" }
' "${reports[@]}" | sort -u
