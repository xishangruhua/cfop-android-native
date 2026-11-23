#!/usr/bin/env bash
set -e
BASE="https://raw.githubusercontent.com/cs0x7f/min2phase/master/src"
echo "[1/2] 正在下载 cs0x7f/min2phase 纯 Java 极速求解器源码..."
for f in CoordCube.java CubieCube.java Search.java Tools.java Util.java; do
  curl -fsSL "$BASE/$f" -o "app/src/main/java/cs/min2phase/$f"
done

echo "[2/2] 正在下载离线版 three.min.js 到 assets ..."
curl -fsSL "https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js" -o "app/src/main/assets/three.min.js"

echo "完成！现在请用 Android Studio 打开本目录即可直接编译运行。"
