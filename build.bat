@echo off
setlocal
cd /d "%~dp0"

rem Rebuild langmgr.jar from src. Pure ASCII on purpose (cmd codepage safe).

set "JAVAC=javac"
set "JAR=jar"
if defined JAVA_HOME (
  if exist "%JAVA_HOME%\bin\javac.exe" set "JAVAC=%JAVA_HOME%\bin\javac.exe"
  if exist "%JAVA_HOME%\bin\jar.exe" set "JAR=%JAVA_HOME%\bin\jar.exe"
)

if not exist "src\com\langmgr\App.java" (
  echo [ERROR] src\com\langmgr\App.java not found. Run this script from the project root.
  exit /b 1
)

if exist out rmdir /s /q out
mkdir out

"%JAVAC%" -encoding UTF-8 -d out -sourcepath src "src\com\langmgr\App.java"
if errorlevel 1 (
  echo [ERROR] compile failed, see messages above.
  exit /b 1
)

rem Bundle icons into the jar, so jpackage/dist launches also find head-pic.png.
if exist "icons" (
  xcopy /E /I /Y "icons" "out\icons" >nul
  if errorlevel 1 (
    echo [ERROR] failed to copy icons into out\icons.
    exit /b 1
  )
)

"%JAR%" --create --file langmgr.jar --main-class com.langmgr.App -C out .
if errorlevel 1 (
  echo [ERROR] jar packaging failed.
  exit /b 1
)

rem Keep every packaged app-image in sync with the icon sources.
rem AppIcon tries <exe dir>\icons\head-pic.png before falling back to the
rem copy bundled in the jar, so that folder must exist for the "replace icon
rem without repacking" behaviour to work. jpackage --input does NOT create it,
rem hence this step. Names under dist\ are non-ASCII (cmd decodes literal
rem Chinese bytes by codepage), so wildcard the folder instead of hardcoding.
if exist "dist" (
  for /d %%d in ("dist\*") do (
    if exist "%%~fd\runtime" (
      xcopy /E /I /Y "icons" "%%~fd\icons" >nul
      if errorlevel 1 (
        echo [WARN] failed to sync icons into an app-image under dist.
      ) else (
        echo [OK] icons synced into app-image under dist.
      )
      rem The app-image is a snapshot: without this the packaged exe keeps
      rem running the jar from packaging time and silently ignores src changes.
      if exist "%%~fd\app" (
        copy /Y "langmgr.jar" "%%~fd\app\langmgr.jar" >nul
        if errorlevel 1 (
          echo [WARN] failed to sync langmgr.jar into app-image under dist.
        ) else (
          echo [OK] langmgr.jar synced into app-image under dist.
        )
      )
    )
  )
)

echo [OK] langmgr.jar rebuilt.
exit /b 0
