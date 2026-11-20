@echo off
chcp 65001 >nul 2>&1
setlocal EnableExtensions
cd /d "%~dp0"
title TouchBridge → RPE

set "ROOT=%~dp0"
set "VENV=%ROOT%.venv"
set "PYEXE=%VENV%\Scripts\python.exe"
set "EXE=%ROOT%TouchBridge\dist\TouchBridge.exe"
set "LOG=%TEMP%\touchbridge-backend.log"
rem requirements.txt 含 UTF-8 中文注释；中文 Windows 下 pip 默认按 GBK 解码会报 UnicodeDecodeError
set "PYTHONUTF8=1"

echo ============================================
echo   TouchBridge → RPE  ·  Windows 一键启动
echo ============================================
echo.

rem ---------- 1. Python ----------
echo [1/6] 检查 Python ...
set "PY="
where python >nul 2>&1 && set "PY=python"
if not defined PY (
    where py >nul 2>&1 && set "PY=py -3"
)
if not defined PY (
    echo   [错误] 未找到 Python。请安装 Python 3.10+ 并勾选 "Add python.exe to PATH"。
    echo          下载地址：https://www.python.org/downloads/windows/
    pause
    exit /b 1
)

rem ---------- 2. 虚拟环境 ----------
echo [2/6] 准备虚拟环境 .venv ...
if not exist "%PYEXE%" (
    %PY% -m venv "%VENV%"
    if errorlevel 1 (
        echo   [错误] 创建虚拟环境失败。
        pause
        exit /b 1
    )
)

rem ---------- 3. 依赖（requirements.txt 变更时自动重装） ----------
set "STAMP="
for %%F in (requirements.txt) do set "STAMP=%%~tF"
set "OLD="
if exist "%VENV%\.deps-stamp" for /f "usebackq delims=" %%L in ("%VENV%\.deps-stamp") do set "OLD=%%L"

if /i "%OLD%"=="%STAMP%" (
    echo [3/6] 依赖已安装，跳过。
) else (
    echo [3/6] 安装 Python 依赖 ...
    "%PYEXE%" -m pip install --disable-pip-version-check -q -r requirements.txt
    if errorlevel 1 (
        echo   [错误] 依赖安装失败，请检查网络或代理设置。
        pause
        exit /b 1
    )
    >"%VENV%\.deps-stamp" echo %STAMP%
)

rem ---------- 4. 采集器 ----------
echo [4/6] 检查触控板采集器 ...
if exist "%EXE%" (
    echo   已就绪：TouchBridge\dist\TouchBridge.exe
) else (
    where dotnet >nul 2>&1
    if errorlevel 1 (
        echo   [警告] 未安装 .NET 8 SDK，无法构建触控板采集器。
        echo          网页与后端仍会启动，但不会收到任何触点。
        echo          安装后重新运行本脚本即可自动构建：
        echo          https://dotnet.microsoft.com/download/dotnet/8.0
    ) else (
        echo   首次运行，正在构建（约 30 秒）...
        call "%ROOT%TouchBridge\build.bat" /quiet
        if errorlevel 1 (
            echo   [警告] 构建失败，本次仅启动网页与后端。
        )
    )
)

rem ---------- 5. 清理占用端口的旧实例 ----------
echo [5/6] 清理旧实例（8765 / 9000）...
for %%P in (8765 9000) do (
    rem TCP 监听行：Proto 本地 外部 LISTENING PID，PID 是第 5 列
    for /f "tokens=5" %%A in ('netstat -ano ^| findstr /c:"LISTENING" ^| findstr /c:":%%P "') do (
        taskkill /f /pid %%A >nul 2>&1
    )
    rem UDP 行没有 LISTENING 状态且只有 4 列，PID 是第 4 列
    for /f "tokens=4" %%A in ('netstat -ano ^| findstr /c:"UDP" ^| findstr /c:":%%P "') do (
        taskkill /f /pid %%A >nul 2>&1
    )
)
taskkill /f /im TouchBridge.exe >nul 2>&1

rem ---------- 6. 启动 ----------
echo [6/6] 启动后端与采集器 ...
start "TouchBridge Backend" /min powershell -NoProfile -Command "& '%PYEXE%' '%ROOT%backend\app.py' *> '%LOG%'"

timeout /t 3 /nobreak >nul

if exist "%EXE%" start "" "%EXE%"

echo.
echo 后端日志：%LOG%
echo 网页地址：http://127.0.0.1:8765 （后端会自动打开浏览器）
echo.
echo 采集器窗口出现后，把手指放到触控板上即可看到触点与 OSC 消息。
echo 关闭本窗口不会停止服务；需要停止时，再次双击本脚本会自动清理旧实例。
echo.
pause
