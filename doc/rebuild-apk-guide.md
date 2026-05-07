# RikkaHub 修改后重新打包 APK 流程

这份文档记录从“修改项目代码”到“重新打包 APK”的完整流程，适合新手按步骤操作。

## 1. 进入项目目录

先进入 RikkaHub 项目的根目录：

```bash
cd /home/default/Projects/rikkahub
```

确认当前目录里有这些文件或目录：

```text
app/
build.gradle.kts
gradlew
settings.gradle.kts
```

如果能看到这些内容，说明你已经在正确的项目根目录。

## 2. 修改代码

这个项目是原生 Android 项目，主要技术是 Kotlin 和 Jetpack Compose。

常见代码位置：

```text
app/src/main/java/        App 主代码和界面代码
app/src/main/res/         Android 资源文件，比如字符串、图片、颜色
ai/                       AI 服务抽象层
common/                   通用工具代码
document/                 文档解析相关代码
search/                   搜索功能相关代码
tts/                      文字转语音相关代码
```

修改完成后，建议先保存所有文件，再继续下一步。

## 3. 准备 google-services.json

项目 README 里写了：

```text
You need a google-services.json file at app folder to build the app.
```

意思是：打包前需要把 Firebase/Google 服务配置文件放到 `app/` 目录下。

最终路径应该是：

```text
/home/default/Projects/rikkahub/app/google-services.json
```

也就是项目里应该存在这个文件：

```bash
ls app/google-services.json
```

如果这个命令能显示文件路径，说明文件已经放好了。

如果没有这个文件，构建时可能会报错。这个文件通常需要从 Firebase 控制台下载，包名要对应项目里的应用 ID：

```text
me.rerere.rikkahub
```

注意：`google-services.json` 可能包含你的 Firebase 项目信息，不建议随便上传到公开仓库。

## 4. 构建 Debug APK

新手建议先打 Debug 包，因为它不需要你自己配置发布签名。

在项目根目录运行：

```bash
./gradlew assembleDebug
```

第一次执行会比较慢，因为 Gradle 需要下载依赖。后面再打包通常会快一些。

如果构建成功，终端一般会看到类似：

```text
BUILD SUCCESSFUL
```

Debug APK 输出位置一般是：

```text
app/build/outputs/apk/debug/
```

这个项目开启了 ABI 分包，同时也会生成 universal 包。你可以查看生成的 APK：

```bash
ls app/build/outputs/apk/debug/
```

常见文件可能包括：

```text
app-arm64-v8a-debug.apk
app-x86_64-debug.apk
app-universal-debug.apk
```

如果你不确定选哪个，通常选 universal 包：

```text
app-universal-debug.apk
```

它体积可能更大，但兼容性更方便。

## 5. 安装到手机测试

如果你有 Android 手机，并且已经打开 USB 调试，可以用 adb 安装：

```bash
adb install app/build/outputs/apk/debug/app-universal-debug.apk
```

如果手机上已经安装过同包名的 Debug 版本，可以加 `-r` 覆盖安装：

```bash
adb install -r app/build/outputs/apk/debug/app-universal-debug.apk
```

注意：Debug 版本的包名会带 `.debug` 后缀，因为项目配置里写了：

```kotlin
applicationIdSuffix = ".debug"
```

所以 Debug 版本和正式版本一般可以同时安装。

## 6. 构建 Release APK

如果你要打正式发布包，可以运行：

```bash
./gradlew assembleRelease
```

Release APK 输出位置一般是：

```text
app/build/outputs/apk/release/
```

不过 Release 包需要签名。项目会从根目录的 `local.properties` 读取签名配置：

```properties
storeFile=/你的/签名文件路径/release.jks
storePassword=你的签名文件密码
keyAlias=你的 key 别名
keyPassword=你的 key 密码
```

如果你没有签名文件，`assembleRelease` 可能会失败，或者生成不能正常安装/发布的包。

新手如果只是自己测试，优先使用：

```bash
./gradlew assembleDebug
```

## 7. 常见问题

### 问题 1：提示找不到 google-services.json

原因：缺少 Firebase 配置文件。

解决：把 `google-services.json` 放到：

```text
app/google-services.json
```

### 问题 2：第一次打包特别慢

原因：Gradle 正在下载 Android Gradle Plugin、Kotlin、Compose、第三方依赖。

解决：等待下载完成。以后再次打包会更快。

### 问题 3：assembleRelease 失败

原因：Release 包通常需要签名配置。

解决：如果只是自己测试，先用 Debug 包：

```bash
./gradlew assembleDebug
```

如果要发布，再准备 keystore，并配置 `local.properties`。

### 问题 4：不知道 APK 在哪里

先查看 Debug 输出目录：

```bash
ls app/build/outputs/apk/debug/
```

再查看 Release 输出目录：

```bash
ls app/build/outputs/apk/release/
```

## 8. 推荐的新手流程

最简单的一套流程是：

```bash
cd /home/default/Projects/rikkahub
ls app/google-services.json
./gradlew assembleDebug
ls app/build/outputs/apk/debug/
```

如果成功，优先拿这个文件安装测试：

```text
app/build/outputs/apk/debug/app-universal-debug.apk
```
