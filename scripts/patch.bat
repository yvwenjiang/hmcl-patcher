@echo off
chcp 65001 >nul
setlocal

set "HERE=%~dp0"
set "JAR=%HERE%..\build\libs\hmcl-patcher.jar"

if not exist "%JAR%" (
    echo 未找到 %JAR%
    echo 请先构建: gradlew build
    exit /b 1
)

java -jar "%JAR%" %*
exit /b %ERRORLEVEL%
