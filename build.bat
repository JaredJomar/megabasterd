@echo off
echo Compiling MegaBasterd with Maven...
cd "b:\Documents\Github\Personals-Projects\Projects\megabasterd"
"C:\ProgramData\apache-maven-3.9.10\bin\mvn.cmd" clean package -DskipTests
echo.
echo Compilation finished.
pause
