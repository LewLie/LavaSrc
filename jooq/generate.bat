@echo off
echo JOOQ will generate files, modify datasource.xml file to set database parameters and other JOOQ configurations.
echo Note: Java17 is required.
pause
java -classpath libs/*;. org.jooq.codegen.GenerationTool datasource.xml
echo ---------------
echo Proceso finalizado
pause
exit
