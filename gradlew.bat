@echo off
setlocal
set APP_HOME=%~dp0
set JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar
if not exist "%JAR%" (
  echo Missing %JAR%
  echo Run bootstrap-wrapper.ps1 once, or run: gradle wrapper --gradle-version 9.6.0
  exit /b 1
)
if defined JAVA_HOME (
  "%JAVA_HOME%\bin\java.exe" -jar "%JAR%" %*
) else (
  java -jar "%JAR%" %*
)
