@echo off
setlocal
cd /d "%~dp0"
set "WRAPPER=gradle\wrapper\gradle-wrapper.jar"
set "EXPECTED=81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f"
if not exist "%WRAPPER%" (
  echo Downloading official Gradle 8.13 wrapper...
  powershell -NoProfile -Command "Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-8.13-wrapper.jar' -OutFile '%WRAPPER%'"
  if errorlevel 1 exit /b 1
)
for /f %%H in ('powershell -NoProfile -Command "(Get-FileHash -Algorithm SHA256 '%WRAPPER%').Hash.ToLower()"') do set "ACTUAL=%%H"
if not "%ACTUAL%"=="%EXPECTED%" (
  echo SECURITY: unexpected Gradle wrapper checksum. 1>&2
  del "%WRAPPER%"
  exit /b 1
)
java -cp "%WRAPPER%" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
