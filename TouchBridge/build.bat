@echo off
chcp 65001 >nul 2>&1
setlocal
cd /d "%~dp0"

rem 用法：build.bat [/self-contained] [/quiet]
set "OUT=%~dp0dist"
set "SELF=--no-self-contained"
if /i "%~1"=="/self-contained" set "SELF="
if /i "%~2"=="/self-contained" set "SELF="
if /i "%~1"=="/quiet" set "TB_QUIET=1"
if /i "%~2"=="/quiet" set "TB_QUIET=1"

where dotnet >nul 2>&1
if errorlevel 1 (
    echo [错误] 未找到 dotnet 命令。
    echo         请安装 .NET 8 SDK：https://dotnet.microsoft.com/download/dotnet/8.0
    if not defined TB_QUIET pause
    exit /b 1
)

echo 正在发布触控板采集器（win-x64，单文件）...
dotnet publish TouchBridge.csproj -c Release -r win-x64 -p:PublishSingleFile=true %SELF% -o "%OUT%" --nologo
if errorlevel 1 (
    echo [错误] 发布失败，请查看上面的编译器输出。
    if not defined TB_QUIET pause
    exit /b 1
)

if not exist "%OUT%\TouchBridge.exe" (
    echo [错误] 发布完成但未找到 TouchBridge.exe。
    if not defined TB_QUIET pause
    exit /b 1
)

echo.
echo 已生成：%OUT%\TouchBridge.exe
echo 自检用法：TouchBridge.exe --osctest
if not defined TB_QUIET pause
exit /b 0
