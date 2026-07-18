@echo off
REM ── Start the APK Automation Testing Framework dashboard ──
setlocal
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
set "JAR=%~dp0target\app-test-framework.jar"

if not exist "%JAR%" (
  echo Jar not found. Building it first with Maven...
  call "C:\Users\Windows\tools\apache-maven-3.9.9\bin\mvn.cmd" -f "%~dp0pom.xml" clean package -DskipTests || exit /b 1
)

echo Starting dashboard at http://localhost:8080  (sign in: admin / admin)
echo Press Ctrl+C to stop.
"%JAVA_HOME%\bin\java.exe" -jar "%JAR%"
endlocal
