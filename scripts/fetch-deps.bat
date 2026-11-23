@echo off
chcp 65001 >nul
echo [1/2] 正在下载 cs0x7f/min2phase 纯 Java 极速求解器源码...
set BASE=https://raw.githubusercontent.com/cs0x7f/min2phase/master/src
for %%F in (CoordCube.java CubieCube.java Search.java Tools.java Util.java) do (
  powershell -Command "Invoke-WebRequest -Uri '%BASE%/%%F' -OutFile 'app/src/main/java/cs/min2phase/%%F'"
)

echo [2/2] 正在下载离线版 three.min.js 到 assets ...
powershell -Command "Invoke-WebRequest -Uri 'https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js' -OutFile 'app/src/main/assets/three.min.js'"

echo 完成！现在请用 Android Studio 打开本目录即可直接编译运行。
pause
