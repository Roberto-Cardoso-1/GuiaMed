@echo off
set "EMULATOR=C:\Users\João Roberto\AppData\Local\Android\Sdk\emulator\emulator.exe"
set "EMULATOR_DIR=C:\Users\João Roberto\AppData\Local\Android\Sdk\emulator"
set "CAMERA_IMAGE=C:\meu-sistema\GuiaMed\app\build\test-camera\ibuprofeno-600.png"

start "GuiaMed Emulator" /D "%EMULATOR_DIR%" "%EMULATOR%" ^
  -avd Medium_Phone_API_37.0 ^
  -no-snapshot ^
  -no-boot-anim ^
  -cores 2 ^
  -gpu host ^
  -camera-back "imagefile:%CAMERA_IMAGE%"
