#!/usr/bin/env bash
#
# hmcl-patcher 启动脚本（Linux / macOS）
#
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
jar="$here/../build/libs/hmcl-patcher.jar"

if [[ ! -f "$jar" ]]; then
    echo "未找到 $jar" >&2
    echo "请先构建: (cd \"$here/..\" && ./gradlew build)" >&2
    exit 1
fi

if ! command -v java >/dev/null 2>&1; then
    echo "未找到 java，请安装 JRE 17 或更高版本" >&2
    exit 1
fi

exec java -jar "$jar" "$@"
