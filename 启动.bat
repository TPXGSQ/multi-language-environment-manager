@echo off
setlocal
cd /d "%~dp0"

rem ============================================================
rem  Multi-Language Environment Manager - launcher
rem  This file is intentionally PURE ASCII (no Chinese bytes):
rem  cmd.exe decodes .bat files with the active console codepage,
rem  which varies per machine (936 / 65001 / ...). Any non-ASCII
rem  byte here would be misparsed and break the script.
rem  All rich Chinese UI lives inside the Java app itself.
rem ============================================================

set "JAR=%~dp0langmgr.jar"
set "SRC=%~dp0src"
set "OUT=%~dp0out"

rem ---------- locate Java runtime ----------
set "JAVA_CMD="
set "JAVAW_CMD="
if defined JAVA_HOME (
  if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
  if exist "%JAVA_HOME%\bin\javaw.exe" set "JAVAW_CMD=%JAVA_HOME%\bin\javaw.exe"
)
if not defined JAVA_CMD (
  for /f "delims=" %%i in ('where java 2^>nul') do (
    if not defined JAVA_CMD set "JAVA_CMD=%%i"
  )
)
if not defined JAVAW_CMD (
  for /f "delims=" %%i in ('where javaw 2^>nul') do (
    if not defined JAVAW_CMD set "JAVAW_CMD=%%i"
  )
)
if not defined JAVA_CMD (
  echo [ERROR] Java runtime not found.
  echo This tool requires JDK 11 or newer.
  echo Please install a JDK, or set JAVA_HOME to your JDK folder.
  pause
  exit /b 1
)

rem ---------- compile from source when jar is missing ----------
if not exist "%JAR%" (
  echo [INFO] langmgr.jar not found, compiling from source...
  if not exist "%SRC%" (
    echo [ERROR] source folder src not found, cannot compile.
    pause
    exit /b 1
  )
  if not exist "%OUT%" mkdir "%OUT%"
  set "JAVAC_CMD="
  if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\javac.exe" set "JAVAC_CMD=%JAVA_HOME%\bin\javac.exe"
  )
  if not defined JAVAC_CMD (
    for /f "delims=" %%i in ('where javac 2^>nul') do (
      if not defined JAVAC_CMD set "JAVAC_CMD=%%i"
    )
  )
  if not defined JAVAC_CMD (
    echo [ERROR] javac not found. Install a full JDK, not just a JRE.
    pause
    exit /b 1
  )
  rem Compile App.java with -sourcepath so javac pulls in every referenced
  rem class transitively. This avoids writing a file list whose Chinese path
  rem bytes would depend on the console codepage (dir redirection) and on
  rem javac's arg-file charset, which disagree on JDK 18+.
  "%JAVAC_CMD%" -encoding UTF-8 -d "%OUT%" -sourcepath "%SRC%" "%SRC%\com\langmgr\App.java"
  if errorlevel 1 (
    echo [ERROR] compile failed, see messages above.
    pause
    exit /b 1
  )
  set "JAR_CMD="
  if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\jar.exe" set "JAR_CMD=%JAVA_HOME%\bin\jar.exe"
  )
  if not defined JAR_CMD (
    for /f "delims=" %%i in ('where jar 2^>nul') do (
      if not defined JAR_CMD set "JAR_CMD=%%i"
    )
  )
  if defined JAR_CMD "%JAR_CMD%" --create --file "%JAR%" --main-class com.langmgr.App -C "%OUT%" .
)

rem ---------- launch GUI ----------
rem Prefer javaw: console-free, so no black window stays behind.
set "RUN_CMD=%JAVA_CMD%"
if defined JAVAW_CMD set "RUN_CMD=%JAVAW_CMD%"
if exist "%JAR%" (
  start "" "%RUN_CMD%" "-Dfile.encoding=UTF-8" -jar "%JAR%"
) else (
  start "" "%RUN_CMD%" "-Dfile.encoding=UTF-8" -cp "%OUT%" com.langmgr.App
)
endlocal
