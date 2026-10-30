@echo off
setlocal
cd /d "%~dp0"
where gradle >nul 2>nul
if errorlevel 1 (
  echo Gradle nao foi encontrado neste Windows.
  echo Abra esta pasta no IntelliJ IDEA com suporte ao Gradle, ou instale o Gradle 8.7.
  echo Tambem pode enviar o projeto ao GitHub e baixar o artefato compilado em Actions.
  pause
  exit /b 1
)
gradle build
if errorlevel 1 (
  echo A compilacao falhou. Copie o erro completo para a conversa para corrigirmos.
  pause
  exit /b 1
)
echo.
echo Compilado! Procure o JAR em build\libs.
pause
