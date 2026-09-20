# 跑本仓库的测试：三种方式，按环境退化
#
#   pwsh -File tools/run-tests.ps1                       # 全量
#   pwsh -File tools/run-tests.ps1 MessageHistoryServiceTest   # 指定类（简单名或全限定名）
#
# 方式 1（首选）：Maven Surefire —— 真实环境、CI 就是这个。
# 方式 2（沙箱兜底）：Surefire 需要 `cmd.exe` 管道才能 fork，受限沙箱会拒绝
#   （CreatePipe error=5）。此时改用 JUnit Platform Launcher 在进程内跑，
#   Mockito 以 -javaagent 方式加载（自附加同样走管道，会被拒）。
# 详细成因与其它环境坑见 docs/agents/dev-environment.md。
#
# 工作目录恒定为 spike/：测试里有依赖相对路径的用例（RuleMappingSuggesterEvalTest）。
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Selectors
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$spike = Join-Path $repo 'spike'
$mvn = Join-Path $repo '.tools\apache-maven-3.9.9\bin\mvn.cmd'
$scratch = Join-Path $repo '.scratch\test-runner'
$m2 = Join-Path $env:USERPROFILE '.m2\repository'

$mvnArgs = @('-o', 'test')
if ($Selectors.Count -gt 0) {
    $mvnArgs = @('-o', "-Dtest=$($Selectors -join ',')", 'test')
}

Push-Location $spike
try {
    # ---- 方式 1：Surefire ----
    & $mvn @mvnArgs
    if ($LASTEXITCODE -eq 0) {
        exit 0
    }

    # ---- 方式 2：进程内 JUnit Platform ----
    Write-Host '[run-tests] Surefire 不可用（沙箱拒绝 fork？），回退到进程内 runner' -ForegroundColor Yellow

    & $mvn -o -q '-DskipTests' 'test-compile'
    if ($LASTEXITCODE -ne 0) { throw 'test-compile 失败，先修编译错误' }
    & $mvn -o -q 'dependency:build-classpath' "-Dmdep.outputFile=$scratch\cp.txt" '-Dmdep.includeScope=test'
    if ($LASTEXITCODE -ne 0) { throw 'dependency:build-classpath 失败' }

    $deps = (Get-Content "$scratch\cp.txt" -Raw).Trim()
    $launcher = "$m2\org\junit\platform\junit-platform-launcher\1.11.4\junit-platform-launcher-1.11.4.jar"
    $mockitoAgent = "$m2\org\mockito\mockito-core\5.14.2\mockito-core-5.14.2.jar"
    if (-not (Test-Path $launcher)) { throw "缺少 $launcher（先联网跑一次 mvn dependency:go-offline）" }

    New-Item -ItemType Directory -Force $scratch | Out-Null
    & "$env:JAVA_HOME\bin\javac.exe" -cp "$deps;$launcher" -d $scratch (Join-Path $PSScriptRoot 'JUnitRunner.java')
    if ($LASTEXITCODE -ne 0) { throw 'runner 编译失败' }

    $targets = if ($Selectors.Count -gt 0) { $Selectors } else { @('com.example.edgegateway') }
    # 简易名（如 FooTest）补全成 FQCN，否则按包选择
    $resolved = $targets | ForEach-Object {
        if ($_ -match '\.') { $_ }
        elseif ($_ -match 'Test$') {
            (Get-ChildItem -Recurse -Filter "$_.java" src\test\java |
                Select-Object -First 1 |
                ForEach-Object { $_.FullName.Substring((Resolve-Path 'src\test\java').Path.Length + 1) -replace '\\', '.' -replace '\.java$', '' })
        }
        else { $_ }
    }

    $cp = "target\classes;target\test-classes;$scratch;$deps;$launcher"
    # 注意：单个测试类在进程内单独跑时，MyBatis-Plus 的 TableInfo 缓存与 Camel 上下文
    # 可能缺少别的用例建立的初始化状态，会出现"全量绿、单类红"的假失败。以全量结果为准。
    & "$env:JAVA_HOME\bin\java.exe" "-javaagent:$mockitoAgent" -cp $cp JUnitRunner @resolved
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
