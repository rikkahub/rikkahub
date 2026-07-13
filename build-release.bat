@echo off
setlocal EnableExtensions

cd /d "%~dp0"

set "RUN_TESTS=1"
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
    call gradlew.bat clean
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
call gradlew.bat --stacktrace %GRADLE_TASKS%
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
echo Usage: build-release.bat [--check] [--clean] [--skip-tests] [--bundle]
echo.
echo   --check        Validate tools and required configuration only.
echo   --clean        Run Gradle clean before building.
echo   --skip-tests   Skip Gradle test and lintRelease tasks.
echo   --bundle       Build an Android App Bundle instead of APK files.
exit /b 0

:usage_error
echo Usage: build-release.bat [--check] [--clean] [--skip-tests] [--bundle]
exit /b 2
