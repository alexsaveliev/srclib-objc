@echo off
"%JAVA_HOME%/bin/java.exe" -Xmx4g -classpath "%~dp0/srclib-objc.jar" com.sourcegraph.toolchain.objc.Main %*
