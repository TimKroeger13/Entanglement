@echo off
rem Builds EntanglementOverlay.dll (x64) into ..\resources\windows\ so it ships with the app.
rem Needs either MSVC ("x64 Native Tools Command Prompt") or zig on the PATH.
setlocal
cd /d "%~dp0"
set OUT=..\resources\windows
if not exist "%OUT%" mkdir "%OUT%"

where cl >nul 2>nul
if %errorlevel%==0 (
    cl /nologo /O2 /W4 /LD overlay.c /Fe:"%OUT%\EntanglementOverlay.dll" /link /DEF:overlay.def ole32.lib uuid.lib shell32.lib advapi32.lib user32.lib
    del /q overlay.obj "%OUT%\EntanglementOverlay.exp" "%OUT%\EntanglementOverlay.lib" 2>nul
    goto :eof
)

where zig >nul 2>nul
if %errorlevel%==0 (
    zig cc -target x86_64-windows-gnu -shared -O2 -Wall -o "%OUT%\EntanglementOverlay.dll" overlay.c overlay.def -lole32 -luuid -lshell32 -ladvapi32 -luser32
    del /q "%OUT%\EntanglementOverlay.pdb" "%OUT%\EntanglementOverlay.lib" 2>nul
    goto :eof
)

echo Neither cl (MSVC) nor zig found. 1>&2
exit /b 1
