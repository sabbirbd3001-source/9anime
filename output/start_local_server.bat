@echo off
cd /d "%~dp0"
echo ========================================================
echo  Local Download Server for Mobile
echo ========================================================
echo.
echo Make sure your phone and PC are on the same Wi-Fi.
echo Find your PC's IP address (e.g. 192.168.x.x) and on your phone browser, open:
echo.
echo    http://YOUR-PC-IP:8080/NineAnimesProvider.cs3
echo.
python -m http.server 8080
pause
