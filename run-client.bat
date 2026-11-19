@echo off
rem Launch Minecraft with the mod. Usage: run-client.bat [gradle task]
rem If JAVA_HOME points to a folder without Java, ignore it and use the java on PATH.
if defined JAVA_HOME if not exist "%JAVA_HOME%\bin\java.exe" set "JAVA_HOME="
set "TASK=%~1"
if "%TASK%"=="" set "TASK=runClient"
call "%~dp0gradlew.bat" %TASK%
if errorlevel 1 pause
