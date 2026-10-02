# Stop on error
$ErrorActionPreference = "Stop"

# Clean up JAVA_HOME path in case it has quotes
$env:JAVA_HOME = $env:JAVA_HOME.Trim('"')

# Create directories if they don't exist
$dirs = @("bin", "classes", "res", "src", "build")
foreach ($dir in $dirs) {
    if (-not (Test-Path $dir)) {
        New-Item -ItemType Directory -Path $dir | Out-Null
    }
}

# Clean up build and classes
Remove-Item -Recurse -Force build\* -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force classes\* -ErrorAction SilentlyContinue

# Find all .java files in src
$javaFiles = Get-ChildItem -Recurse -Path src -Filter *.java | ForEach-Object { $_.FullName }
# Run preprocessing
node sdk/preprocess.js @javaFiles manifest.mf midlets.pro $env:DEFINES

New-Item -ItemType Directory -Path build\res -Force | Out-Null
Copy-Item res\* build\res -Recurse -Force

# Remove excluded files, if any
if ($env:EXCLUDES) {
    # Split EXCLUDES by spaces
    $excludes = $env:EXCLUDES -split '[ ]+' | Where-Object { $_ -ne "" }

    Set-Location build\res
    foreach ($file in $excludes) {
        Remove-Item -Recurse -Force $file -ErrorAction SilentlyContinue
    }
    Set-Location ../..
}

$javac = Join-Path $env:JAVA_HOME "bin\javac"
$javaFilesToCompile = Get-ChildItem -Recurse -Path build\src -Filter *.java | ForEach-Object { $_.FullName }
& $javac @javaFilesToCompile `
    -d classes `
    -source 1.2 `
    -target 1.2 `
    -nowarn `
    -encoding UTF-8 `
    -bootclasspath $env:BOOTCLASSPATH `
    *> sdk/log.txt
# Note: use -nowarn to suppress all warnings, because deprecation warning is somehow treated as an error

$jar = Join-Path $env:JAVA_HOME "bin\jar.exe" -resolve
# BouncyCastle (pure-Java TLS for hosts the native TLS can't reach - see pubtran.JavaTls):
# extracted once and bundled into targets whose bootclasspath lists lib/bouncycastle.jar.
if ($env:MODCON -eq 1 -and -Not (Test-Path "lib\bouncycastle\org")) {
    Write-Host "Extracting libraries"
    New-Item -ItemType Directory -Path "lib\bouncycastle" -Force | Out-Null
    Set-Location "lib\bouncycastle"
    & $jar xf ../bouncycastle.jar
    Set-Location "..\.."
    if (-Not (Test-Path "lib\bouncycastle\org")) {
        throw "Failed to extract lib\bouncycastle.jar"
    }
}

# BouncyCastle first, then the app's classes: src/org/bouncycastle/... replaces a few
# BouncyCastle classes (smaller curve tables), and the later update overwrites them.
if ($env:MODCON -eq 1) {
    & $jar cvf bin/in.jar -C lib/bouncycastle . >> sdk/log.txt
    & $jar uvf bin/in.jar -C classes . -C build/res . >> sdk/log.txt
} else {
    & $jar cvf bin/in.jar -C classes . -C build/res . >> sdk/log.txt
}
& $jar uvfm bin/in.jar build/manifest.mf >> sdk/log.txt

$java = Join-Path $env:JAVA_HOME "bin\java"
& $java -jar sdk/proguard.jar @build/midlets.pro -printmapping "bin/$($env:JAR_NAME).map" *>> sdk/log.txt
Remove-Item bin/in.jar
Move-Item bin/out.jar "bin/$($env:JAR_NAME).jar" -Force

# Show output JAR file size
$jarPath = "bin/$($env:JAR_NAME).jar"
$jarSizeKB = [math]::Round((Get-Item $jarPath).Length / 1KB, 1)
Write-Host "Built $jarPath ($jarSizeKB KB)"
