@echo off
setlocal
cd /d "%~dp0"
chcp 65001 >nul 2>&1
set "PY=%~dp0.venv\Scripts\python.exe"
if exist "%PY%" goto install
where python >nul 2>&1
if errorlevel 1 goto no_python
python -m venv .venv
if errorlevel 1 goto fail
:install
"%PY%" -m pip install -r requirements-build.txt --disable-pip-version-check
if errorlevel 1 goto fail
call collector\build.bat /self-contained /quiet
if errorlevel 1 goto fail
cd /d "%~dp0"
"%PY%" tests\test_engine.py
if errorlevel 1 goto fail
"%PY%" -m unittest discover -s tests -v
if errorlevel 1 goto fail
set "DATA_BACKUP=%~dp0build\TouchBridgeRPE-data-backup"
if exist "%DATA_BACKUP%" goto backup_exists
if not exist "dist\TouchBridgeRPE\data" goto package
if not exist build mkdir build
move "dist\TouchBridgeRPE\data" "%DATA_BACKUP%" >nul
if errorlevel 1 goto fail
:package
"%PY%" -m PyInstaller --noconfirm TouchBridgeRPE.spec
if errorlevel 1 goto fail
if not exist "%DATA_BACKUP%" goto copy_docs
move "%DATA_BACKUP%" "dist\TouchBridgeRPE\data" >nul
if errorlevel 1 goto fail
:copy_docs
copy /y README.md dist\TouchBridgeRPE\README.md >nul
if errorlevel 1 goto fail
copy /y LICENSE dist\TouchBridgeRPE\LICENSE >nul
if errorlevel 1 goto fail
copy /y AI_DISCLOSURE.md dist\TouchBridgeRPE\AI_DISCLOSURE.md >nul
if errorlevel 1 goto fail
echo.
echo 构建完成：dist\TouchBridgeRPE\TouchBridgeRPE.exe
echo 请分发整个 TouchBridgeRPE 目录，不要只复制 exe。
exit /b 0
:no_python
echo 构建需要 Python 3.10+ x64 和 .NET 8 SDK，最终用户无需安装这些工具。
exit /b 1
:backup_exists
echo 检测到上次构建保留的数据：%DATA_BACKUP%
echo 请先恢复并移走此备份目录，再重新构建，避免覆盖数据。
exit /b 1
:fail
echo 构建失败，请检查上方输出。
if defined DATA_BACKUP if exist "%DATA_BACKUP%" echo 原发布目录的数据保留在：%DATA_BACKUP%
exit /b 1
