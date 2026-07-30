call mvn clean package -Dmaven.test.skip=true

cd target
rename valerius-openfire-plugin-assembly.jar valerius.jar
del "D:\Openfire\openfire_5_0_2\plugins\valerius.jar" 
del /q "D:\Openfire\openfire_5_0_2\logs\*.*"
copy valerius.jar D:\Openfire\openfire_5_0_2\plugins\valerius.jar
pause