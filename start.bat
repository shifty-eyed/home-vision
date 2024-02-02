@echo off
set LIB_DIR=target\lib
set MAIN_CLASS=org.homevision.Application
set MAIN_JAR=home-vision-0.1.jar

REM Build the classpath from the jars in the lib directory and the main jar
for /r %LIB_DIR% %%g in (*.jar) do call :concat %%g
set CLASSPATH=%MAIN_JAR%;%CLASSPATH%

REM Run the application
java -Djava.library.path=%LIB_DIR% -cp "%CLASSPATH%" %MAIN_CLASS%
goto :eof

:concat
set CLASSPATH=%CLASSPATH%;%1
goto :eof
