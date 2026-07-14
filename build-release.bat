@echo off
setlocal EnableExtensions

cd /d "%~dp0"

set "RUN_TESTS=0"
set "RUN_CLEAN=0"
set "BUILD_BUNDLE=0"
set "CHECK_ONLY=0"

:parse_args
if "%~1"=="" goto args_done
if /I "%~1"=="--skip-tests" (
    set "RUN_TESTS=0"
    shift
    goto parse_args
)
if /I "%~1"=="--with-tests" (
    set "RUN_TESTS=1"
    shift
    goto parse_args
)
if /I "%~1"=="--clean" (
    set "RUN_CLEAN=1"
    shift
    goto parse_args
)
if /I "%~1"=="--bundle" (
    set "BUILD_BUNDLE=1"
    shift
    goto parse_args
)
if /I "%~1"=="--check" (
    set "CHECK_ONLY=1"
    shift
    goto parse_args
)
if /I "%~1"=="--help" goto usage
if /I "%~1"=="-h" goto usage

echo [ERROR] Unknown option: %~1
goto usage_error

:args_done
echo [INFO] Haruhome release build

if not exist "gradlew.bat" (
    echo [ERROR] gradlew.bat was not found in %CD%
    exit /b 1
)

where java.exe >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java was not found. Install JDK 17 and configure JAVA_HOME/PATH.
    exit /b 1
)

where node.exe >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Node.js was not found. Install Node.js 22 LTS.
    exit /b 1
)

where pnpm.cmd >nul 2>&1
if errorlevel 1 (
    echo [ERROR] pnpm was not found. Install pnpm 11.
    exit /b 1
)

if not exist "local.properties" (
    echo [ERROR] local.properties is missing.
    echo [INFO] It must contain sdk.dir and release signing properties.
    exit /b 1
)

for %%K in (sdk.dir storeFile storePassword keyAlias keyPassword) do (
    findstr /B /C:"%%K=" "local.properties" >nul 2>&1
    if errorlevel 1 (
        echo [ERROR] local.properties is missing %%K
        exit /b 1
    )
)

set "SIGN_STORE_FILE="
set "SIGN_STORE_PASSWORD="
set "SIGN_KEY_ALIAS="
set "SIGN_KEY_PASSWORD="
for /F "usebackq tokens=1,* delims==" %%A in ("local.properties") do (
    if /I "%%A"=="storeFile" set "SIGN_STORE_FILE=%%B"
    if /I "%%A"=="storePassword" set "SIGN_STORE_PASSWORD=%%B"
    if /I "%%A"=="keyAlias" set "SIGN_KEY_ALIAS=%%B"
    if /I "%%A"=="keyPassword" set "SIGN_KEY_PASSWORD=%%B"
)

set "SIGN_STORE_PATH=%SIGN_STORE_FILE%"
if not exist "%SIGN_STORE_PATH%" set "SIGN_STORE_PATH=app\%SIGN_STORE_FILE%"
if not exist "%SIGN_STORE_PATH%" (
    echo [ERROR] Release keystore was not found: %SIGN_STORE_FILE%
    exit /b 1
)

where jar.exe >nul 2>&1
if errorlevel 1 (
    echo [ERROR] jar.exe was not found. Use a full JDK 17 installation.
    exit /b 1
)

where jarsigner.exe >nul 2>&1
if errorlevel 1 (
    echo [ERROR] jarsigner.exe was not found. Use a full JDK 17 installation.
    exit /b 1
)

echo [INFO] Validating release keystore and key passwords...
set "SIGN_CHECK_DIR=%TEMP%\haruhome-sign-check-%RANDOM%-%RANDOM%"
mkdir "%SIGN_CHECK_DIR%" >nul 2>&1
echo Haruhome signing check>"%SIGN_CHECK_DIR%\marker.txt"
jar.exe --create --file "%SIGN_CHECK_DIR%\sign-check.jar" -C "%SIGN_CHECK_DIR%" marker.txt >nul 2>&1
if errorlevel 1 goto signing_check_failed

set "HARUHOME_STORE_PASSWORD=%SIGN_STORE_PASSWORD%"
set "HARUHOME_KEY_PASSWORD=%SIGN_KEY_PASSWORD%"
jarsigner.exe -keystore "%SIGN_STORE_PATH%" -storepass:env HARUHOME_STORE_PASSWORD -keypass:env HARUHOME_KEY_PASSWORD "%SIGN_CHECK_DIR%\sign-check.jar" "%SIGN_KEY_ALIAS%" >nul 2>&1
if errorlevel 1 goto signing_check_failed

set "HARUHOME_STORE_PASSWORD="
set "HARUHOME_KEY_PASSWORD="
rmdir /S /Q "%SIGN_CHECK_DIR%" >nul 2>&1
echo [OK] Release signing configuration is valid.

if not exist "web-ui\pnpm-lock.yaml" (
    echo [ERROR] web-ui\pnpm-lock.yaml is missing.
    exit /b 1
)

echo [OK] Required commands and configuration files are present.
java -version
node --version
call pnpm --version
if errorlevel 1 exit /b 1

if "%CHECK_ONLY%"=="1" (
    echo [OK] Environment check completed. No build was run.
    exit /b 0
)

echo [INFO] Initializing Git submodules...
git submodule update --init --recursive
if errorlevel 1 (
    echo [ERROR] Git submodule initialization failed.
    exit /b 1
)

echo [INFO] Installing locked web-ui dependencies...
pushd "web-ui"
call pnpm install --frozen-lockfile
if errorlevel 1 (
    popd
    echo [ERROR] pnpm install failed.
    exit /b 1
)
popd

if "%RUN_CLEAN%"=="1" (
    echo [INFO] Cleaning previous Gradle outputs...
    call gradlew.bat --no-daemon --no-parallel clean
    if errorlevel 1 (
        echo [ERROR] Gradle clean failed.
        exit /b 1
    )
)

set "GRADLE_TASKS="
if "%RUN_TESTS%"=="1" set "GRADLE_TASKS=test lintRelease"
if "%BUILD_BUNDLE%"=="1" (
    set "GRADLE_TASKS=%GRADLE_TASKS% bundleRelease"
) else (
    set "GRADLE_TASKS=%GRADLE_TASKS% assembleRelease"
)

echo [INFO] Running: gradlew.bat %GRADLE_TASKS%
call gradlew.bat --no-daemon --no-parallel --stacktrace %GRADLE_TASKS%
if errorlevel 1 (
    echo [ERROR] Release build failed.
    exit /b 1
)

if "%BUILD_BUNDLE%"=="1" (
    echo [OK] Release App Bundle created:
    dir /B /S "app\build\outputs\bundle\release\*.aab" 2>nul
) else (
    echo [OK] Signed release APK output:
    dir /B /S "app\build\outputs\apk\release\*.apk" 2>nul
)

echo [DONE] Release build completed successfully.
exit /b 0

:usage
echo Usage: build-release.bat [--check] [--clean] [--with-tests] [--bundle]
echo.
echo   --check        Validate tools and required configuration only.
echo   --clean        Run Gradle clean before building.
echo   --with-tests   Run Gradle test and lintRelease before packaging.
echo   --skip-tests   Compatibility option; tests are skipped by default.
echo   --bundle       Build an Android App Bundle instead of APK files.
exit /b 0

:usage_error
echo Usage: build-release.bat [--check] [--clean] [--with-tests] [--bundle]
exit /b 2

:signing_check_failed
set "HARUHOME_STORE_PASSWORD="
set "HARUHOME_KEY_PASSWORD="
rmdir /S /Q "%SIGN_CHECK_DIR%" >nul 2>&1
echo [ERROR] Release signing validation failed.
echo [INFO] Check storeFile, storePassword, keyAlias, and keyPassword in local.properties.
exit /b 1
