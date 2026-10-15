# 一键构建「趵突泉之光」APK（不使用 Gradle）
# aapt2 编译资源 -> aapt2 link 生成基础 APK 与 R.java -> javac -> d8 -> 打包 -> zipalign -> apksigner
#
# 需要准备的东西（都可用环境变量指定）：
#   $env:ANDROID_SDK_ROOT  Android SDK 根目录，需含 build-tools\35.0.0 与 platforms\android-34
#   $env:JAVA_HOME         JDK 21；未设置时从 PATH 里找 javac
#   $env:WATER_KS_PASS     签名口令；未设置时读同目录 keystore.pass，再不行就交互输入
#
# 注意：d8 必须用 build-tools 35.0.0 —— 34.0.0 遇到匿名内部类会 NPE。

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$PROJ  = $PSScriptRoot
$BUILD = Join-Path $PROJ 'build'
$REPO  = Split-Path $PROJ -Parent
$OUT   = Join-Path $REPO 'build'

# ---- 定位 Android SDK ----
$SDK = $env:ANDROID_SDK_ROOT
if (-not $SDK) { $SDK = $env:ANDROID_HOME }
if (-not $SDK) { $SDK = Join-Path $REPO '..\tools\android-sdk' }
if (-not (Test-Path $SDK)) {
    throw '找不到 Android SDK，请设置 $env:ANDROID_SDK_ROOT 指向 SDK 根目录。'
}
$BT   = Join-Path $SDK 'build-tools\35.0.0'
$AJAR = Join-Path $SDK 'platforms\android-34\android.jar'

# ---- 定位 JDK ----
$JDKBIN = $null
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) {
    $JDKBIN = Join-Path $env:JAVA_HOME 'bin'
} else {
    $j = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($j) { $JDKBIN = Split-Path $j.Source -Parent }
}
if (-not $JDKBIN) {
    # 没设 JAVA_HOME 就在仓库旁边的 tools\ 里找一个（和 SDK 的兜底逻辑一致）
    $toolsDir = Join-Path $REPO '..\tools'
    if (Test-Path $toolsDir) {
        $j = Get-ChildItem $toolsDir -Filter javac.exe -Recurse -ErrorAction SilentlyContinue |
             Where-Object { $_.DirectoryName -like '*\bin' } | Select-Object -First 1
        if ($j) { $JDKBIN = $j.DirectoryName }
    }
}
if (-not $JDKBIN) { throw '找不到 javac，请设置 $env:JAVA_HOME 或把 JDK 的 bin 加进 PATH。' }

foreach ($p in @($AJAR, (Join-Path $BT 'aapt2.exe'), (Join-Path $BT 'd8.bat'),
                 (Join-Path $BT 'zipalign.exe'), (Join-Path $BT 'apksigner.bat'))) {
    if (-not (Test-Path $p)) { throw "缺少: $p" }
}
$env:JAVA_HOME = Split-Path $JDKBIN -Parent
$env:PATH = "$JDKBIN;$BT;$env:PATH"

if (Test-Path $BUILD) { Remove-Item $BUILD -Recurse -Force }
New-Item -ItemType Directory -Force -Path $BUILD, (Join-Path $BUILD 'classes'),
    (Join-Path $BUILD 'gen'), (Join-Path $BUILD 'dex'), $OUT | Out-Null

function Step($n) { Write-Host ""; Write-Host "==> $n" -ForegroundColor Cyan }

# ---- 1) 编译资源 ----
Step 'aapt2 compile (res)'
& (Join-Path $BT 'aapt2.exe') compile --dir (Join-Path $PROJ 'res') -o (Join-Path $BUILD 'res.zip')
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile 失败' }

# ---- 2) 链接资源 + 生成 R.java ----
Step 'aapt2 link'
& (Join-Path $BT 'aapt2.exe') link `
    -o (Join-Path $BUILD 'base.apk') `
    -I $AJAR `
    --manifest (Join-Path $PROJ 'AndroidManifest.xml') `
    -R (Join-Path $BUILD 'res.zip') `
    --java (Join-Path $BUILD 'gen') `
    --min-sdk-version 24 `
    --target-sdk-version 34 `
    --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link 失败' }

# ---- 3) 编译 Java ----
Step 'javac'
$ZXING = Join-Path $PROJ 'libs\zxing-core.jar'
if (-not (Test-Path $ZXING)) { throw "缺少二维码库: $ZXING" }
$javaFiles = @(Get-ChildItem (Join-Path $PROJ 'java') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$javaFiles += @(Get-ChildItem (Join-Path $BUILD 'gen') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
Write-Host "    源文件 $($javaFiles.Count) 个"
# 原生程序往 stderr 写东西时 PS 5.1 会当成错误：日志收进文件，只在失败时显示
$javacLog = Join-Path $BUILD 'javac.log'
$ErrorActionPreference = 'Continue'
& (Join-Path $JDKBIN 'javac.exe') -source 8 -target 8 -nowarn -Xlint:none `
    -bootclasspath $AJAR `
    -classpath $ZXING `
    -encoding UTF-8 `
    -d (Join-Path $BUILD 'classes') `
    $javaFiles 2>&1 | Out-File -FilePath $javacLog -Encoding UTF8
$javacCode = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($javacCode -ne 0) {
    Get-Content $javacLog -Encoding UTF8 | Select-Object -Last 25 | ForEach-Object { Write-Host "    $_" }
    throw 'javac 失败'
}

# ---- 4) 转 dex ----
Step 'd8'
# 先打成 jar 再交给 d8，避免内部类文件名里的 $ 在 cmd 传参时出问题
$jar = Join-Path $BUILD 'classes.jar'
& (Join-Path $JDKBIN 'jar.exe') cf $jar -C (Join-Path $BUILD 'classes') .
if ($LASTEXITCODE -ne 0) { throw 'jar 打包失败' }
$d8Log = Join-Path $BUILD 'd8.log'
$ErrorActionPreference = 'Continue'
& (Join-Path $BT 'd8.bat') --lib $AJAR --min-api 24 --output (Join-Path $BUILD 'dex') $jar $ZXING 2>&1 |
    Out-File -FilePath $d8Log -Encoding UTF8
$d8Code = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($d8Code -ne 0) {
    Get-Content $d8Log -Encoding UTF8 | Select-Object -Last 25 | ForEach-Object { Write-Host "    $_" }
    throw 'd8 失败'
}
if (-not (Test-Path (Join-Path $BUILD 'dex\classes.dex'))) { throw 'classes.dex 未生成' }

# ---- 5) 把 classes.dex 塞进 APK ----
Step '打包 classes.dex'
Copy-Item (Join-Path $BUILD 'base.apk') (Join-Path $BUILD 'app-unsigned.apk') -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apkPath = Join-Path $BUILD 'app-unsigned.apk'
$zip = [System.IO.Compression.ZipFile]::Open($apkPath, 'Update')
$entry = $zip.GetEntry('classes.dex')
if ($entry) { $entry.Delete() }
[void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
    $zip, (Join-Path $BUILD 'dex\classes.dex'), 'classes.dex',
    [System.IO.Compression.CompressionLevel]::Optimal)
$zip.Dispose()

# ---- 6) zipalign ----
Step 'zipalign'
& (Join-Path $BT 'zipalign.exe') -f -p 4 (Join-Path $BUILD 'app-unsigned.apk') (Join-Path $BUILD 'app-aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'zipalign 失败' }


# ---- 7) 签名 ----
Step 'apksigner'
$ks = Join-Path $PROJ 'keystore.jks'
# 口令来源：环境变量 > keystore.pass（本机文件，已被 .gitignore 排除）> 交互输入
$ksPass = $env:WATER_KS_PASS
if (-not $ksPass) {
    $passFile = Join-Path $PROJ 'keystore.pass'
    if (Test-Path $passFile) { $ksPass = ([IO.File]::ReadAllText($passFile)).Trim() }
}
if (-not $ksPass -and (Test-Path $ks)) {
    $sec = Read-Host '    输入 keystore 口令' -AsSecureString
    $ksPass = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
        [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec))
}
if (-not (Test-Path $ks)) {
    if (-not $ksPass) {
        $alphabet = 'abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'
        $ksPass = -join (1..28 | ForEach-Object { $alphabet[(Get-Random -Max $alphabet.Length)] })
        [IO.File]::WriteAllText((Join-Path $PROJ 'keystore.pass'), $ksPass)
        Write-Host '    首次构建：已生成随机口令，存到 keystore.pass（勿提交）' -ForegroundColor Yellow
    }
    Write-Host '    生成签名密钥 keystore.jks（仅本机保存）'
    $ErrorActionPreference = 'Continue'
    & (Join-Path $JDKBIN 'keytool.exe') -genkeypair -v `
        -keystore $ks -alias water -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass $ksPass -keypass $ksPass `
        -dname "CN=Self Water, O=Personal, C=CN" 2>&1 | Out-Null
    $ErrorActionPreference = 'Stop'
}
$final = Join-Path $OUT 'water-release.apk'
& (Join-Path $BT 'apksigner.bat') sign `
    --ks $ks --ks-pass "pass:$ksPass" --key-pass "pass:$ksPass" `
    --ks-key-alias water `
    --out $final (Join-Path $BUILD 'app-aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'apksigner 失败' }
