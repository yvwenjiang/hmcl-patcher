#!/usr/bin/env bash
#
# 发布 hmcl-patcher 到 GitHub。
#
# 令牌请通过 `gh auth login` 或 GH_TOKEN 环境变量提供，不要写进命令行参数或本脚本，
# 否则会留在 shell 历史和进程列表里，容易泄露。
#
# 用法:
#   gh auth login          # 首次使用，按提示登录
#   ./scripts/publish.sh   # 创建仓库（如果没有）、推送、构建、发布 release
#
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

REPO_NAME="${REPO_NAME:-hmcl-patcher}"
VERSION="$(grep -m1 '^version' build.gradle.kts | sed -E 's/.*"([^"]+)".*/\1/')"
TAG="${TAG:-v$VERSION}"
ASSET="build/libs/hmcl-patcher.jar"

command -v git >/dev/null 2>&1 || { echo "需要先安装 git" >&2; exit 1; }
command -v gh >/dev/null 2>&1 || {
    echo "需要 GitHub CLI（gh）：https://cli.github.com/" >&2
    exit 1
}

if ! gh auth status >/dev/null 2>&1; then
    cat >&2 <<'EOF'
尚未登录 GitHub。

请执行:  gh auth login
或先导出环境变量:  export GH_TOKEN=<你的令牌>

不要直接把令牌写在命令行参数里——它会进入 shell 历史和进程列表。
EOF
    exit 1
fi

if ! git remote get-url origin >/dev/null 2>&1; then
    echo "==> 创建仓库 $REPO_NAME 并推送"
    gh repo create "$REPO_NAME" --public --source=. --remote=origin --push
else
    echo "==> 推送到已有仓库"
    git push -u origin HEAD
fi

echo "==> 构建（含 ProGuard 混淆）"
./gradlew build --no-daemon

echo "==> 发布 $TAG"
git tag -f "$TAG" >/dev/null
git push -f origin "$TAG"
if ! gh release view "$TAG" >/dev/null 2>&1; then
    gh release create "$TAG" "$ASSET" \
        --title "$TAG" \
        --notes "hmcl-patcher $TAG（已混淆的独立 jar，运行需要 JRE 17+）"
else
    gh release upload "$TAG" "$ASSET" --clobber
fi

echo "==> 完成: $(gh repo view --json url -q .url 2>/dev/null || echo "$REPO_NAME")"
