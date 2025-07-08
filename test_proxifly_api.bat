@echo off
echo ================================================================
echo                    PROXIFLY FREE PROXY TESTER
echo ================================================================
echo.

echo Testing connection with Proxifly Free Proxy Service...
echo This service provides free proxies without requiring an API key.
echo.

REM Test different proxy types
echo ---- TESTING HTTP PROXIES ----
echo URL: https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/protocols/http/data.txt
curl -w "HTTP Status: %%{http_code}\n" ^
     -s ^
     -o temp_http_proxies.txt ^
     https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/protocols/http/data.txt

setlocal enabledelayedexpansion
for /f "delims=" %%A in ('curl -w "%%{http_code}" -s -o NUL https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/protocols/http/data.txt') do set "HTTP_RESULT=%%A"
echo HTTP Proxies Status: !HTTP_RESULT!

if "!HTTP_RESULT!"=="200" (
    for /f %%i in ('find /c /v "" temp_http_proxies.txt') do set HTTP_COUNT=%%i
    echo [200] SUCCESS: HTTP proxy list downloaded successfully. Found !HTTP_COUNT! proxies.
    echo First 5 HTTP proxies:
    for /f "tokens=* skip=0" %%a in ('more +0 temp_http_proxies.txt') do (
        set /a line_count+=1
        if !line_count! leq 5 echo %%a
    )
) else (
    echo [!HTTP_RESULT!] ERROR: Failed to download HTTP proxy list.
)
echo.

echo ---- TESTING SOCKS5 PROXIES ----
echo URL: https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/protocols/socks5/data.txt
for /f "delims=" %%A in ('curl -w "%%{http_code}" -s -o temp_socks5_proxies.txt https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/protocols/socks5/data.txt') do set "SOCKS5_RESULT=%%A"
echo SOCKS5 Proxies Status: !SOCKS5_RESULT!

if "!SOCKS5_RESULT!"=="200" (
    for /f %%i in ('find /c /v "" temp_socks5_proxies.txt') do set SOCKS5_COUNT=%%i
    echo [200] SUCCESS: SOCKS5 proxy list downloaded successfully. Found !SOCKS5_COUNT! proxies.
    echo First 5 SOCKS5 proxies:
    set line_count=0
    for /f "tokens=*" %%a in (temp_socks5_proxies.txt) do (
        set /a line_count+=1
        if !line_count! leq 5 echo %%a
    )
) else (
    echo [!SOCKS5_RESULT!] ERROR: Failed to download SOCKS5 proxy list.
)
echo.

echo ---- TESTING ALL PROXIES ----
echo URL: https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/all/data.txt
for /f "delims=" %%A in ('curl -w "%%{http_code}" -s -o temp_all_proxies.txt https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/all/data.txt') do set "ALL_RESULT=%%A"
echo All Proxies Status: !ALL_RESULT!

if "!ALL_RESULT!"=="200" (
    for /f %%i in ('find /c /v "" temp_all_proxies.txt') do set ALL_COUNT=%%i
    echo [200] SUCCESS: Complete proxy list downloaded successfully. Found !ALL_COUNT! total proxies.
) else (
    echo [!ALL_RESULT!] ERROR: Failed to download complete proxy list.
)
endlocal

echo.
echo.
echo ================================================================
echo                        DIAGNOSTICS
echo ================================================================
echo.
echo If you see status 200: Proxy list downloaded successfully
echo If you see status 404: Proxy list not found (URL may have changed)
echo If you see status 403: Access forbidden
echo If you see connection error: Network problem or CDN issue
echo.
echo Note: This is a FREE service - no API key required!
echo Proxy lists are updated every 5 minutes automatically.
echo.
echo For more info visit: https://github.com/proxifly/free-proxy-list
echo.
echo ================================================================

REM Clean up temporary files
if exist temp_http_proxies.txt del temp_http_proxies.txt
if exist temp_socks5_proxies.txt del temp_socks5_proxies.txt  
if exist temp_all_proxies.txt del temp_all_proxies.txt

pause
