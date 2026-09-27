<#
.SYNOPSIS
    PEAK 图鉴后端 HTTP 冒烟验收脚本（issue #17 / 父票 #11 的「测试决定」）。

.DESCRIPTION
    对**运行中的后端**发真实请求，一次穿过控制器、采集、转换、持久层、静态资源映射，
    用「已知答案断言」给转换逻辑做端到端回归保护。

    断言分四组：
      1. POST /admin/crawl        —— 采集报告的统计与明细
      2. GET  /testItemList       —— 全量列表的已知答案断言（转换逻辑的端到端回归）
      3. GET  /icons/Hot_Dog.png  —— 图标静态路径是 200 PNG
      4. GET  /api/items|tags|biomes —— 小程序契约：筛选收窄、模糊查询、按 slug 取详情

    全部断言打印 PASS/FAIL 明细，最后汇总退出码：全过 0，有 FAIL 非 0。

.PARAMETER BaseUrl
    后端基地址，默认 http://localhost:8080。

.PARAMETER TimeoutSeconds
    等待后端 ready 的上限，默认 180 秒（后端冷启动约 10 秒，留足余量）。

.PARAMETER CrawlTimeoutSeconds
    POST /admin/crawl 的单项超时，默认 600 秒。真实采集要拉数据源全量行、
    约 3 包页面源文、134 张图标，实测约 60 秒，但数据源在境外，抖动时会更久。

.EXAMPLE
    先起后端（工作目录必须是 guide/，图标要落到 guide/icons/）：
        cd guide && mvn -o spring-boot:run

    另开一个终端，在仓库根跑：
        powershell -ExecutionPolicy Bypass -File scripts/smoke-test.ps1

.EXAMPLE
    换端口：
        powershell -ExecutionPolicy Bypass -File scripts/smoke-test.ps1 -BaseUrl http://localhost:9090

.NOTES
    数据源被 Cloudflare 拦死时本脚本变红是**正确信号**（触发离线导入降级预案），不是误报。
#>
[CmdletBinding()]
param(
    [string] $BaseUrl = 'http://localhost:8080',
    [int]    $TimeoutSeconds = 180,
    [int]    $CrawlTimeoutSeconds = 600
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# 采集报告里的 fetchedRows 是整数下限断言值：wiki 增删物品不误报，故意不用精确值 134
$MIN_ROWS = 130

$BaseUrl = $BaseUrl.TrimEnd('/')

# redis-cli 的可执行路径：缓存那组断言用它删 key（那个破坏性的 POST 验收端点已停用）。
# 允许用环境变量覆盖；默认取本机开发环境的路径，找不到也不报错 —— 到用的时候
# 会作为一条断言失败呈现（此时 Redis 不可达那组本来就已经跳过了）。
$script:RedisCli = if ($env:GUIDE_REDIS_CLI) { $env:GUIDE_REDIS_CLI }
    elseif (Test-Path 'D:\redis\redis-cli.exe') { 'D:\redis\redis-cli.exe' }
    else { 'redis-cli' }

# ─────────────────────────────────────────────────────────────────────────────
# 断言记账
# ─────────────────────────────────────────────────────────────────────────────

$script:Passed = 0
$script:Failed = 0
$script:Notes  = New-Object System.Collections.Generic.List[string]

function Add-Pass([string] $name) {
    $script:Passed++
    Write-Host ("  [PASS] " + $name)
}

function Add-Fail([string] $name, [string] $detail) {
    $script:Failed++
    Write-Host ("  [FAIL] " + $name) -ForegroundColor Red
    Write-Host ("         " + $detail) -ForegroundColor Red
}

function Add-Note([string] $text) {
    $script:Notes.Add($text)
    Write-Host ("  [INFO] " + $text) -ForegroundColor DarkGray
}

# 断言只接受真布尔：传进来的若是数组（例如误写成 `Assert-True $x.someArray`），
# PowerShell 会按"非空即真"处理，一条本该 FAIL 的断言会静默变 PASS。这里显式挡掉。
function Assert-True($condition, [string] $name, [string] $detail) {
    if ($condition -isnot [bool]) {
        $condition = [bool]$condition
    }
    if ($condition) { Add-Pass $name } else { Add-Fail $name $detail }
}

function Format-Actual($value) {
    if ($null -eq $value) { return '(null)' }
    if ($value -is [System.Array]) { return (($value | ForEach-Object { "$_" }) -join ', ') }
    return [string]$value
}

function Assert-Equal($actual, $expected, [string] $name) {
    if ($actual -is [System.Array]) {
        Add-Fail $name ("实际是个数组（" + (Format-Actual $actual) + "），断言写法有问题：应取标量再比")
        return
    }
    $ok = ($null -ne $actual) -and ($actual -eq $expected)
    Assert-True $ok $name ("期望 " + (Format-Actual $expected) + "，实际 " + (Format-Actual $actual))
}

function Assert-GreaterOrEqual($actual, $expected, [string] $name) {
    if ($actual -is [System.Array] -or $null -eq $actual) {
        Add-Fail $name ("期望 ≥ " + $expected + "，实际 " + (Format-Actual $actual))
        return
    }
    $ok = ([double]$actual -ge [double]$expected)
    Assert-True $ok $name ("期望 ≥ " + $expected + "，实际 " + (Format-Actual $actual))
}

function Section([string] $title) {
    Write-Host ''
    Write-Host ("── " + $title + " " + ('─' * [Math]::Max(0, 62 - $title.Length))) -ForegroundColor Cyan
}

# ─────────────────────────────────────────────────────────────────────────────
# HTTP 辅助
# ─────────────────────────────────────────────────────────────────────────────

function Wait-BackendReady {
    Write-Host ("等后端 ready：" + $BaseUrl + " …") -ForegroundColor Yellow
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $waited = 0
    while ((Get-Date) -lt $deadline) {
        try {
            $null = Invoke-WebRequest -Uri "$BaseUrl/testItemList" -Method Get -TimeoutSec 5 -UseBasicParsing
            Write-Host ("  后端已就绪（等了 " + $waited + " 秒）") -ForegroundColor Green
            return $true
        } catch {
            # 连接被拒 / 404 都算"还没就绪"：Tomcat 起来前连不上，起来后 Shiro 之前可能先给 404。
            # 只有真正拿到 HTTP 响应才算就绪，所以这里连 404 都不接受。
            Start-Sleep -Seconds 2
            $waited += 2
            if ($waited % 30 -eq 0) { Write-Host ("  等后端 ready（已等 " + $waited + " 秒）") }
        }
    }
    return $false
}

# 发一个请求并解出 JSON。
#
# ⚠️ 必须用 Invoke-RestMethod，**不能**用 Invoke-WebRequest + ConvertFrom-Json：
# PS 5.1 的 Invoke-WebRequest 给的 `Content` 是个 String（不是字节流也不是对象数组），
# 于是 `$resp.Content | ConvertFrom-Json` 这个管道**一个元素都没枚举出来** ——
# ConvertFrom-Json 收到的是整个 JSON 文本，把顶层数组"摊平"成单个对象，
# 结果 `.Count` 是 1、`$items[0].nameEn` 是 134 个名字拼成的一长串。
# 那种坏法是静默的：不报错，只是后面每一条按 nameEn 找条目的断言都找不到人。
# Invoke-RestMethod 把顶层数组原样还原成 Object[]，`.Count` 才是 134。
#
# 中文断言要注意：后端的 Content-Type 是 `application/json`，**不带 charset**，
# 于是 PS 5.1 按 ISO-8859-1 解码响应体，`message` / `nameZh` 这类中文字段在脚本里读出来是乱码
# （实测 `"操作成功"` 变成 `"æä½æå"`，服务端本身是对的）。
# 所以断言一律挑 ASCII 字段比对（slug / nameEn / code / primaryType），
# 非要验中文取值就自己把 RawContentStream 按 UTF-8 解一遍。
#
# $Headers / $Body 都可省略 —— 省略时就是一个裸 GET，上面四组断言照旧。
# 教学端点要的是 POST + token 头 + JSON body，两条都从这里的参数走：
#   $Headers 是散列表（如 @{ token = 'oXXX' }），$Body 是任意对象，
#   非 null 时序列化成 JSON 文本再发，并显式写 Content-Type。
#
# ⚠️ 序列化必须用 [System.Text.Encoding]::UTF8.GetBytes 拿**字节**、且 $Body 传 -Body 而不是
# 塞进 -Headers：PS 5.1 的 Invoke-RestMethod 把字符串当 body 时按 ISO-8859-1 编码，
# 中文入参（第 4 组后面加的 AI 教学断言里会有）到服务端就成了乱码。
# [string]$body 这一句也是必需的：PSCustomObject 直接传 -Body 会被当成"字段=值"的多部分解析。
function Invoke-Json([string] $method, [string] $path, [int] $timeoutSec, $Headers, $Body) {
    $uri = $BaseUrl + $path
    $params = @{
        Uri             = $uri
        Method          = $method
        TimeoutSec      = $timeoutSec
        UseBasicParsing = $true
    }
    if ($null -ne $Headers) { $params.Headers = $Headers }
    if ($null -ne $Body) {
        $params.ContentType = 'application/json'
        $params.Body = [System.Text.Encoding]::UTF8.GetBytes(([string](ConvertTo-Json -InputObject $Body -Compress -Depth 10)))
    }
    try {
        $json = Invoke-RestMethod @params
        return @{
            Ok     = $true
            Status = 200
            Json   = $json
        }
    } catch {
        $status = $null
        if ($_.Exception.Response) {
            try { $status = [int]$_.Exception.Response.StatusCode } catch { }
        }
        # 非 2xx 时 Invoke-RestMethod 会抛异常，响应体在 $_.ErrorDetails.Message（PS 5.1）。
        # Shiro 的 TokenAuthFilter 对未登录回 HTTP 401 + 响应壳 code=401，教学端点那几条 401
        # 断言读的就是这里解析出来的 code。取不到（或不是 JSON）就留 $null —— 只看 Status 的
        # 断言不受影响，取不到的旁边会记一条 INFO，不当失败。
        $errorJson = $null
        try {
            if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
                $errorJson = ConvertFrom-Json -InputObject ([string]$_.ErrorDetails.Message)
            }
        } catch { $errorJson = $null }
        return @{
            Ok     = $false
            Status = $status
            Json   = $errorJson
            Error  = $_.Exception.Message
        }
    }
}

# 统一响应包是 {success, code, message, data}（契约 §0.1），业务字段在 data **里面**。
#
# ⚠️ 少剥这一层的断言不会红，只会**静默空转**：`Get-Field $resp['Json'] 'items'` 恒为 $null，
# 经 As-Array 变成空数组，于是逐条循环一条都不进、每条断言都"通过"。
# 本轮实测踩到过：`/api/items 返回全量 134 条` 报"实际 0"，而同组的逐条断言全绿。
#
# ⚠️ 信封本身是 Invoke-Json 返回的 **hashtable**，取它的字段只能用 `$resp['Error']` 这种**下标**写法，
# 两种想当然的写法都是坑：
#   - `Get-Field $resp 'Error'` 恒得 $null 且不报错 —— hashtable 的 PSObject.Properties 里
#     只有 Count / Keys / Values 这些，业务键**不在**里面；
#   - `$resp.Error` 属性写法会在 Set-StrictMode Latest 下**当场抛** —— Error 只在失败分支才有。
# Get-Field 只拿来读解出来的 JSON 对象（PSCustomObject），不要往信封上套。
function Get-Data($response) {
    return Get-Field ($response['Json']) 'data'
}

# 只取 JSON 里的字段，字段不存在时返回 $null（Set-StrictMode 下不能裸访问不存在的属性）。
#
# ⚠️ 数组要**原样带出来**：PowerShell 在函数返回时会把空数组（`"effect":[]`）解包成 $null ——
# 数据源里真的有条目一个状态效果都没有（Scout's Ambition 就是），少了下面这一手，
# "空数组"会被误判成"字段是 null"，于是"JSON 列映射静默失效"这条回归保护会对着正确数据报 FAIL。
# 而标量（字符串/数字）**不能**包：包了就成了单元素数组，`Get-Field $x 'code' -eq 'Food'`
# 这种比较会静默变成 False。所以按类型分开走，两种值都原样返回。
function Get-Field($obj, [string] $name) {
    if ($null -eq $obj) { return $null }
    $prop = $obj.PSObject.Properties[$name]
    if ($null -eq $prop) { return $null }
    if ($prop.Value -is [System.Array]) { return ,$prop.Value }
    return $prop.Value
}

# 把 Get-Field 的返回值当数组用：标量包成单元素数组，数组原样，null 得空数组。
#
# ⚠️ 三条 return 的**前置逗号都是必需的**，原因见上面的 Get-Field：
# PowerShell 在函数返回时会解包空数组。空失败清单（`"failures":[]` 这种最常见的正常情形）
# 少了逗号就会变成 $null，于是 `.Count` 在 StrictMode 下当场抛异常。
# 逗号包一层之后，调用方拿到的一定是数组：空数组也还是空数组。
function As-Array($value) {
    if ($null -eq $value) { return ,@() }
    if ($value -is [System.Array]) { return ,$value }
    return ,@($value)
}

# tag / effect 数组里按 code 取值；同一个 code 出现多次时（不该发生）取第一条并记一条 INFO。
#
# 用 foreach 显式循环而不是 `Where-Object { (Get-Field $_ 'code') -eq $code }`：
# `$_` 在嵌套函数调用里不可靠，那个写法的过滤结果会静默为空
# （断言于是变成"条目明明在库里却找不到标签/效果"这种假 FAIL）。
function Get-Effect($item, [string] $code) {
    $found = @()
    foreach ($e in (As-Array (Get-Field $item 'effect'))) {
        if ((Get-Field $e 'code') -eq $code) { $found += $e }
    }
    if ($found.Count -gt 1) { Add-Note ((Get-Field $item 'nameEn') + " 的 effect 里 " + $code + " 出现 " + $found.Count + " 次，断言取第一条") }
    if ($found.Count -eq 0) { return $null }
    return $found[0]
}

function Get-Tag($item, [string] $code) {
    $found = @()
    foreach ($t in (As-Array (Get-Field $item 'tag'))) {
        if ((Get-Field $t 'code') -eq $code) { $found += $t }
    }
    return ,$found
}

# 详情接口的 raw / cooked 是**已经拆好**的数组（契约 §2.1），入参就是数组本身，
# 不能复用上面按 item.effect 取值的 Get-Effect —— 那个的入参是条目、读的是 effect 列。
function Get-EffectByCode($effects, [string] $code) {
    foreach ($e in (As-Array $effects)) {
        if ((Get-Field $e 'code') -eq $code) { return $e }
    }
    return $null
}

function Test-HasTagValue($item, [string] $code, [string] $value) {
    foreach ($t in (Get-Tag $item $code)) {
        if ((Get-Field $t 'value') -eq $value) { return $true }
    }
    return $false
}

function Get-Item([object[]] $items, [string] $nameEn) {
    foreach ($i in $items) {
        if ((Get-Field $i 'nameEn') -eq $nameEn) { return $i }
    }
    return $null
}

# 一个数组的字段全貌，失败信息里好读：["type=Food", "flag=cookable"]
function Format-Tags($tags) {
    $list = As-Array $tags
    if ($list.Count -eq 0) { return '(无)' }
    $parts = @()
    foreach ($t in $list) { $parts += ((Get-Field $t 'code') + '=' + (Get-Field $t 'value')) }
    return '[' + ($parts -join ', ') + ']'
}

function Format-Effect($effect) {
    if ($null -eq $effect) { return '(该 effect 不存在)' }
    $parts = @('value=' + (Get-Field $effect 'value'))
    if ($null -ne (Get-Field $effect 'duration'))   { $parts += 'duration=' + (Get-Field $effect 'duration') }
    if ($null -ne (Get-Field $effect 'startDelay')) { $parts += 'startDelay=' + (Get-Field $effect 'startDelay') }
    return '(' + ($parts -join ', ') + ')'
}

# ─────────────────────────────────────────────────────────────────────────────
# 开跑
# ─────────────────────────────────────────────────────────────────────────────

Write-Host ''
Write-Host '═══ PEAK 图鉴 HTTP 冒烟验收 ═══' -ForegroundColor White
Write-Host ("目标后端：" + $BaseUrl)
Write-Host ("开始时间：" + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))

if (-not (Wait-BackendReady)) {
    Write-Host ''
    Write-Host ("后端在 " + $TimeoutSeconds + " 秒内没有就绪，放弃。") -ForegroundColor Red
    Write-Host '先在 guide/ 下起后端：cd guide && mvn -o spring-boot:run' -ForegroundColor Yellow
    exit 1
}

# ── 第 1 组：采集报告 ────────────────────────────────────────────────────────

Section '1. POST /admin/crawl — 采集报告'

Write-Host ("  触发真实采集，最长等 " + $CrawlTimeoutSeconds + " 秒（数据源在境外，拉全量+134 张图标）…")
$crawl = Invoke-Json 'POST' '/admin/crawl' $CrawlTimeoutSeconds

Assert-True ($crawl.Ok -and $crawl.Status -eq 200) '采集接口 HTTP 200' `
    ("实际 status=" + $crawl.Status + " error=" + $crawl['Error'])

if (-not ($crawl.Ok -and $crawl.Status -eq 200)) {
    Write-Host ''
    Write-Host '采集接口没通，后面的列表断言没有意义，直接收尾。' -ForegroundColor Red
    Write-Host ("错误：" + $crawl['Error'])
    exit 1
}

$report = $crawl.Json

# 报告原样留档一份：这是演示日"当场指出使用痕迹"的道具
Write-Host ''
Write-Host '  采集报告 JSON：' -ForegroundColor DarkGray
Write-Host ('    ' + ($report | ConvertTo-Json -Compress -Depth 8)) -ForegroundColor DarkGray

$fetchedRows      = Get-Field $report 'fetchedRows'
$successCount     = Get-Field $report 'successCount'
$iconSuccessCount = Get-Field $report 'iconSuccessCount'
# As-Array：报告里这三个数组字段为空时长度是 0，用于断言与逐条打印
$failures         = As-Array (Get-Field $report 'failures')
$iconFailures     = As-Array (Get-Field $report 'iconFailures')
$warnings         = As-Array (Get-Field $report 'warnings')
$fetchError       = Get-Field $report 'fetchError'
$wikitextError    = Get-Field $report 'wikitextError'

# 数据源被拦时的明确提示 —— 这正是票面设计的"正确信号"，不硬绕
if ($null -ne $fetchError -and $fetchError -ne '') {
    Write-Host ''
    Write-Host '  ┌─ 数据源疑似不可达/被拦 ─────────────────────────────────────┐' -ForegroundColor Magenta
    Write-Host ("  │ fetchError: " + $fetchError)
    Write-Host '  │ 这是正确信号，不是脚本误报：触发离线导入降级预案' -ForegroundColor Magenta
    Write-Host '  │ （见 docs/分工与目标清单.md §6 风险与降级预案）'
    Write-Host '  └─────────────────────────────────────────────────────────────┘' -ForegroundColor Magenta
}

Assert-True (($null -eq $fetchError) -or ($fetchError -eq '')) `
    'fetchError 为空（拉取阶段没挂）' ("fetchError=" + $fetchError)
Assert-GreaterOrEqual $fetchedRows $MIN_ROWS "fetchedRows ≥ $MIN_ROWS"
Assert-GreaterOrEqual $successCount $MIN_ROWS "successCount ≥ $MIN_ROWS"

$failureList = As-Array $failures
if ($failureList.Count -eq 0) {
    Add-Pass 'failures 为空'
} else {
    # 票面要求"为空**或列明**"：这里逐条打印，但断言本身仍然记 FAIL ——
    # 有落库失败就该当场看见，而不是被"已列明"糊过去
    Add-Fail 'failures 为空' ("有 " + $failureList.Count + " 条落库失败，明细如下")
    foreach ($f in $failureList) {
        Write-Host ("           page=" + (Get-Field $f 'page') + " nameEn=" + (Get-Field $f 'nameEn') + " reason=" + (Get-Field $f 'reason')) -ForegroundColor Red
    }
}

Assert-True (($null -eq $wikitextError) -or ($wikitextError -eq '')) `
    'wikitextError 为空（页面源文管道通了）' ("wikitextError=" + $wikitextError)
Assert-GreaterOrEqual $iconSuccessCount $MIN_ROWS "iconSuccessCount ≥ $MIN_ROWS"

$iconFailureList = As-Array $iconFailures
if ($iconFailureList.Count -eq 0) {
    Add-Pass 'iconFailures 为空'
} else {
    Add-Fail 'iconFailures 为空' ("有 " + $iconFailureList.Count + " 张图标失败，明细如下")
    foreach ($f in $iconFailureList) {
        Write-Host ("           nameEn=" + (Get-Field $f 'nameEn') + " reason=" + (Get-Field $f 'reason')) -ForegroundColor Red
    }
}

# warnings 只要求"打印"，不构成 FAIL：字典未知取值是数据源变了，恰好是这条通道要暴露的东西
$warningList = As-Array $warnings
if ($warningList.Count -eq 0) {
    Add-Note 'warnings 为空（字典没有未知取值）'
} else {
    Add-Note ("warnings 有 " + $warningList.Count + " 条：")
    foreach ($w in $warningList) { Write-Host ("           " + $w) -ForegroundColor DarkYellow }
}

# ── 第 2 组：全量列表的已知答案断言 ──────────────────────────────────────────

Section '2. GET /testItemList — 全量列表的已知答案断言'

$list = Invoke-Json 'GET' '/testItemList' 120
Assert-True ($list.Ok -and $list.Status -eq 200) '列表接口 HTTP 200' `
    ("实际 status=" + $list.Status + " error=" + $list['Error'])

if (-not ($list.Ok -and $list.Status -eq 200)) {
    Write-Host ''
    Write-Host '列表接口没通，后面的已知答案断言无法进行，直接收尾。' -ForegroundColor Red
    exit 1
}

$items = @($list.Json)
$total = $items.Count

# 去重后应与行数相等（按 nameEn upsert 的幂等性）。同样用显式 foreach 避开 $_ 的作用域问题
$names = @()
foreach ($i in $items) { $names += (Get-Field $i 'nameEn') }
$distinct = @($names | Sort-Object -Unique).Count

Write-Host ("  列表返回 " + $total + " 行，去重后 " + $distinct + " 个 nameEn")

Assert-GreaterOrEqual $total $MIN_ROWS "行数 ≥ $MIN_ROWS"
Assert-Equal $total $distinct '行数 = 去重后的 nameEn 数（重复采集不产生重复行）'

# JSON 列映射的顺带回归保护：autoResultMap 漏了的话 tag / effect 会静默全 null 且不报错。
# 判据用"不是 null"而不是"非空"：数据源里真的有条目一个状态效果都没有
# （Scout's Ambition 这类纯功能护身符，十个状态列全是空串），
# 而"空字段不进数组"是票内的明确口径 —— 断言非空会把正确行为判成 FAIL。
#
# 这几段用显式 foreach 而不是 `Where-Object { ... Get-Field $_ ... }`，原因见 Get-Effect 上方。
$missingTag = @()
$nullEffect = @()
$badIcon = @()
foreach ($i in $items) {
    if ($null -eq (Get-Field $i 'tag')) { $missingTag += (Get-Field $i 'nameEn') }
    if ($null -eq (Get-Field $i 'effect')) { $nullEffect += (Get-Field $i 'nameEn') }
    if ((Get-Field $i 'icon') -notlike '/icons/*') { $badIcon += (Get-Field $i 'nameEn') }
}

Assert-True ($missingTag.Count -eq 0) '每行的 tag 字段都不是 null（JSON 列映射没静默失效）' `
    ("有 " + $missingTag.Count + " 行 tag 是 null：" + (($missingTag | Select-Object -First 5) -join ', '))
Assert-True ($nullEffect.Count -eq 0) '每行的 effect 字段都不是 null（JSON 列映射没静默失效）' `
    ("有 " + $nullEffect.Count + " 行 effect 是 null：" + (($nullEffect | Select-Object -First 5) -join ', '))
Assert-True ($badIcon.Count -eq 0) '每行 icon 都是以 /icons/ 开头的相对路径' `
    ("有 " + $badIcon.Count + " 行的 icon 不是相对路径：" + (($badIcon | Select-Object -First 5) -join ', '))

# ── Hot Dog ──
Write-Host ''
Write-Host '  Hot Dog：'
$hotDog = Get-Item $items 'Hot Dog'
Assert-True ($null -ne $hotDog) 'Hot Dog 在列表里' '列表里查不到名为 "Hot Dog" 的行'
if ($null -ne $hotDog) {
    Assert-True (Test-HasTagValue $hotDog 'biome' 'Gloom') 'Hot Dog biome=Gloom' ("实际 " + (Format-Tags (Get-Tag $hotDog 'biome')))
    Assert-True (Test-HasTagValue $hotDog 'rarity' 'Rare') 'Hot Dog rarity=Rare' ("实际 " + (Format-Tags (Get-Tag $hotDog 'rarity')))
    $h = Get-Effect $hotDog 'HUNGER'
    Assert-Equal (Get-Field $h 'value') -30 'Hot Dog HUNGER=-30'
    if ((Get-Field $h 'value') -ne -30) { Write-Host ("           实际 " + (Format-Effect $h)) -ForegroundColor Red }
    $hc = Get-Effect $hotDog 'HUNGER_COOKED'
    Assert-Equal (Get-Field $hc 'value') -60 'Hot Dog HUNGER_COOKED=-60（熟食总量）'
    if ((Get-Field $hc 'value') -ne -60) { Write-Host ("           实际 " + (Format-Effect $hc)) -ForegroundColor Red }
}

# ── Bugle Shroom (Poisonous) ──
Write-Host ''
Write-Host '  Bugle Shroom (Poisonous)：'
$poisonShroom = Get-Item $items 'Bugle Shroom (Poisonous)'
Assert-True ($null -ne $poisonShroom) '毒变体独立成行（Bugle Shroom (Poisonous) 在列表里）' `
    '列表里查不到 "Bugle Shroom (Poisonous)" 这一行'
if ($null -ne $poisonShroom) {
    # 独立成行还不够，得确认它没跟普通版共用一条（nameEn 不同即独立）
    $normalShroom = Get-Item $items 'Bugle Shroom'
    Assert-True ($null -ne $normalShroom -and (Get-Field $normalShroom 'nameEn') -ne (Get-Field $poisonShroom 'nameEn')) `
        '毒变体与普通版是两条不同的行' '普通版 "Bugle Shroom" 不在列表里，或两行的 nameEn 相同'

    $p = Get-Effect $poisonShroom 'POISON'
    Assert-Equal (Get-Field $p 'value') 20 '毒变体 POISON=20'
    Assert-Equal (Get-Field $p 'duration') 8 '毒变体 POISON duration=8（poisonTime）'
    Assert-Equal (Get-Field $p 'startDelay') 10 '毒变体 POISON startDelay=10（poisonStart）'
    if ((Get-Field $p 'value') -ne 20 -or (Get-Field $p 'duration') -ne 8 -or (Get-Field $p 'startDelay') -ne 10) {
        Write-Host ("           实际 " + (Format-Effect $p)) -ForegroundColor Red
    }
}

# ── Warp Compass ──
Write-Host ''
Write-Host '  Warp Compass：'
$warpCompass = Get-Item $items 'Warp Compass'
Assert-True ($null -ne $warpCompass) 'Warp Compass 在列表里（已移除条目不过滤）' `
    '列表里查不到 "Warp Compass"'
if ($null -ne $warpCompass) {
    Assert-True (Test-HasTagValue $warpCompass 'flag' 'removed') 'Warp Compass 带 flag=removed' `
        ("实际 " + (Format-Tags (Get-Tag $warpCompass 'flag')))
}

# ── First Aid Kit ──
Write-Host ''
Write-Host '  First Aid Kit：'
$firstAid = Get-Item $items 'First Aid Kit'
Assert-True ($null -ne $firstAid) 'First Aid Kit 在列表里' '列表里查不到 "First Aid Kit"'
if ($null -ne $firstAid) {
    Assert-True (-not (Test-HasTagValue $firstAid 'flag' 'cookable')) 'First Aid Kit 不带 flag=cookable' `
        ("实际 " + (Format-Tags (Get-Tag $firstAid 'flag')))
}

# ── Green Crispberry ──
Write-Host ''
Write-Host '  Green Crispberry：'
$crispberry = Get-Item $items 'Green Crispberry'
Assert-True ($null -ne $crispberry) 'Green Crispberry 在列表里' '列表里查不到 "Green Crispberry"'
if ($null -ne $crispberry) {
    $pc = Get-Effect $crispberry 'POISON_COOKED'
    Assert-True ($null -ne $pc) 'Green Crispberry 有 POISON_COOKED' 'effect 里没有 POISON_COOKED，浆果规则没生效'
    if ($null -ne $pc) {
        Assert-Equal (Get-Field $pc 'value') 0 'Green Crispberry POISON_COOKED=0（浆果煮熟毒清零，显式写 0）'
        if ((Get-Field $pc 'value') -ne 0) { Write-Host ("           实际 " + (Format-Effect $pc)) -ForegroundColor Red }
        # 清零是"煮掉了"，生毒带的附属值不该跟着来 —— 写 0 时只写值，不写 duration/startDelay
        Assert-True ($null -eq (Get-Field $pc 'duration') -and $null -eq (Get-Field $pc 'startDelay')) `
            'Green Crispberry 的 POISON_COOKED 不带 duration/startDelay' ("实际 " + (Format-Effect $pc))
    }
    # 生毒仍在：两侧对照才构成"煮掉了"这条信息
    $pr = Get-Effect $crispberry 'POISON'
    Assert-Equal (Get-Field $pr 'duration') 4 'Green Crispberry 生 POISON duration=4（熟后清零的对照）'
    if ((Get-Field $pr 'duration') -ne 4) { Write-Host ("           实际 " + (Format-Effect $pr)) -ForegroundColor Red }
}

# ── 第 3 组：图标静态路径 ────────────────────────────────────────────────────

Section '3. GET /icons/Hot_Dog.png — 图标静态路径'

# 后端没通时 Invoke-WebRequest 会抛，这里刻意吞掉异常走断言 —— 报错要让断言 FAIL 出来，
# 而不是让脚本当场炸掉、留下一堆没跑完的断言
try {
    $icon = Invoke-WebRequest -Uri "$BaseUrl/icons/Hot_Dog.png" -Method Get -TimeoutSec 30 -UseBasicParsing
} catch {
    $icon = $null
}
$iconStatus = if ($null -eq $icon) { $null } else { [int]$icon.StatusCode }
Assert-True ($iconStatus -eq 200) 'HTTP 200' ("实际 status=" + $(if ($null -eq $iconStatus) { '连不上' } else { $iconStatus }))

if ($iconStatus -eq 200) {
    $contentType = [string]$icon.Headers['Content-Type']
    Assert-True ($contentType -like '*image/png*') 'Content-Type 含 image/png' ("实际 Content-Type=" + $contentType)

    # PNG 魔数：89 50 4E 47 0D 0A 1A 0A。静态映射会把错误页/半张图当成品发出去，所以看内容。
    # 这里 Content 是真正的字节数组（二进制响应不会被解成字符串），不用再做编码转换
    $bytes = $icon.Content
    $magic = @(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    $isPng = ($bytes -is [System.Array]) -and ($bytes.Length -ge 8)
    if ($isPng) {
        for ($i = 0; $i -lt 8; $i++) { if ($bytes[$i] -ne $magic[$i]) { $isPng = $false; break } }
    }
    $head = if ($bytes -is [System.Array]) { (($bytes | Select-Object -First 8 | ForEach-Object { $_.ToString('X2') }) -join ' ') } else { '(不是字节数组)' }
    Assert-True $isPng '响应体以 PNG 魔数开头（真的是图，不是错误页）' ("实际前 8 字节：" + $head)
}

# ── 第 4 组：图鉴接口（小程序契约）────────────────────────────────────────────

Section '4. GET /api/items|tags|biomes — 小程序契约'

# 前端 guide-mini/utils/api.js 启动时并发拉这三个端点，读的是 data.items / data.tags / data.biomes，
# 所以这一组既验服务端行为，也验响应形状对得上前端已经写死的取法。

$PRIMARY_TYPES = @('FOOD', 'CONSUMABLE', 'EQUIPMENT', 'DEPLOYABLE', 'AMULET', 'MYSTICAL', 'MISC', 'ENEMY')

$all = Invoke-Json 'GET' '/api/items' 60
Assert-True ($all.Ok -and $all.Status -eq 200) '/api/items HTTP 200' `
    ("实际 status=" + $all.Status + " error=" + $all['Error'])

$allItems = As-Array (Get-Field (Get-Data $all) 'items')

# 全量口径就是 134（CONTEXT.md 图鉴条目节）。这一条**不设容差**：第 1 组的 fetchedRows 用 ≥130
# 是怕 wiki 增删物品造成误报，而"图鉴有多少条"是产品口径本身，变了就该当场看见
Assert-Equal $allItems.Count 134 '/api/items 返回全量 134 条'

# 字段面 = 契约 §1 的 6 个，多一个少一个都算接口契约漂移：
# 多带 description / descriptionZh 会让 payload 涨三倍，多带 id 是把"换机器重灌就变"的库内序号下发
$LIST_FIELDS = @('slug', 'nameZh', 'nameEn', 'icon', 'weight', 'primaryType')
$FORBIDDEN_FIELDS = @('description', 'descriptionZh', 'tags', 'id')

$fieldMissing = @()
$fieldLeaked = @()
$badPrimaryType = @()
$badSlug = @()
$slugs = @()
foreach ($i in $allItems) {
    $nameEn = Get-Field $i 'nameEn'
    foreach ($f in $LIST_FIELDS) {
        if ($null -eq (Get-Field $i $f)) { $fieldMissing += ($nameEn + '.' + $f) }
    }
    foreach ($f in $FORBIDDEN_FIELDS) {
        if ($null -ne (Get-Field $i $f)) { $fieldLeaked += ($nameEn + '.' + $f) }
    }
    if ((Get-Field $i 'primaryType') -notin $PRIMARY_TYPES) { $badPrimaryType += $nameEn }
    $s = Get-Field $i 'slug'
    if ($null -eq $s -or $s -eq '') { $badSlug += $nameEn } else { $slugs += $s }
}

Assert-True ($fieldMissing.Count -eq 0) ("每行都带齐列表的 6 个字段（" + ($LIST_FIELDS -join ' / ') + "）") `
    ("缺字段的有 " + $fieldMissing.Count + " 处：" + (($fieldMissing | Select-Object -First 5) -join ', '))
Assert-True ($fieldLeaked.Count -eq 0) '列表不带 description / descriptionZh / tags / id（长文本、tags 与库内 id 都不下发）' `
    ("多带字段的有 " + $fieldLeaked.Count + " 处：" + (($fieldLeaked | Select-Object -First 5) -join ', '))
Assert-True ($badPrimaryType.Count -eq 0) '每行的 primaryType 都是 8 个取值之一' `
    ("有 " + $badPrimaryType.Count + " 行的 primaryType 不在取值表里：" + (($badPrimaryType | Select-Object -First 5) -join ', '))
Assert-True ($badSlug.Count -eq 0) '每行都有非空 slug（由 nameEn 派生）' `
    ("有 " + $badSlug.Count + " 行没有 slug：" + (($badSlug | Select-Object -First 5) -join ', '))
Assert-Equal @($slugs | Sort-Object -Unique).Count $allItems.Count 'slug 唯一（详情按 slug 取，撞车就取错条目）'

# ── slug 的已知答案：库里那几组按"删特殊字符"会硬撞的名字 ──
Write-Host ''
Write-Host '  slug 与主类型：'
foreach ($case in @(
        @{ nameEn = 'Hot Dog';                  slug = 'hot_dog' },
        @{ nameEn = 'Bugle?';                   slug = 'bugle_' },
        @{ nameEn = 'Bugle Shroom (Poisonous)'; slug = 'bugle_shroom_(poisonous)' })) {
    $target = Get-Item $allItems $case.nameEn
    Assert-True ($null -ne $target) ($case.nameEn + ' 在列表里') ('列表里查不到 ' + $case.nameEn)
    if ($null -ne $target) {
        Assert-Equal (Get-Field $target 'slug') $case.slug ($case.nameEn + ' → slug ' + $case.slug)
    }
}

# 跨界条目：Scorpion 同时带 Food 与 Enemy，一级导航只能有一个落点，落在食物（契约 §0.5）
$scorpion = Get-Item $allItems 'Scorpion'
Assert-True ($null -ne $scorpion) 'Scorpion 在列表里' '列表里查不到 Scorpion'
if ($null -ne $scorpion) {
    Assert-Equal (Get-Field $scorpion 'primaryType') 'FOOD' 'Scorpion（Food + Enemy）归 FOOD'
}

# 已移除条目不过滤（明确的产品决定，契约 §0.5）
foreach ($removed in @('Bugle?', 'Warp Compass')) {
    Assert-True ($null -ne (Get-Item $allItems $removed)) ($removed + ' 在列表里（已移除条目不过滤）') `
        ('列表里查不到 ' + $removed)
}

# 返回顺序：按 primaryType 分组（组序见契约 §0.5）。
# "组内按 id 升序"这半边客户端验不了 —— 接口有意不下发 id（契约 §1），这里只钉分组这一层
$lastGroupIndex = 0
$orderBreaks = @()
foreach ($i in $allItems) {
    $groupIndex = [array]::IndexOf($PRIMARY_TYPES, (Get-Field $i 'primaryType'))
    if ($groupIndex -lt $lastGroupIndex) { $orderBreaks += (Get-Field $i 'nameEn') }
    $lastGroupIndex = $groupIndex
}
Assert-True ($orderBreaks.Count -eq 0) 'primaryType 按契约的分组顺序返回（FOOD → CONSUMABLE → … → ENEMY）' `
    ("有 " + $orderBreaks.Count + " 处顺序回退，第一处是 " + (($orderBreaks | Select-Object -First 1) -join ''))

# ── 筛选参数不生效：接口只返全量，一级导航与关键词搜索都在小程序本地做 ──
#
# ⚠️ 这一段**只断言"服务端忽略这些参数"**，不在这里验筛选本身。
# 契约 §0.5 与 Q2 已经收口：/api/items **不接受任何筛选参数**，一次返全量 134 条、不分页；
# 带不带 ?primaryType= / ?keyword= 都必须返回同一份全量。
#
# 为什么专门写一段"参数被忽略"的断言而不是把这段删掉：旧版脚本在这里断言的是
# `primaryType=FOOD 的结果非空且少于全量`。服务端忽略参数后返回的是全量 134 条，
# 那条断言因为 `134 < 134` 是假而报 FAIL —— 一并的 `primaryType 入参不区分大小写` 则是**假 PASS**：
# 返回的全量里当然能找到 EQUIPMENT。假 PASS 比 FAIL 危险，它会把"参数被静默忽略"这种漂移藏起来，
# 所以这里把口径反过来钉死：带参数 = 不带参数 = 全量 134 条。
Write-Host ''
Write-Host '  筛选参数（已收口为"服务端忽略"）：'
foreach ($case in @(
        @{ path = '/api/items?primaryType=FOOD';       label = 'primaryType=FOOD' },
        @{ path = '/api/items?primaryType=equipment';  label = 'primaryType=equipment（小写）' },
        @{ path = '/api/items?keyword=hot%20dog';      label = 'keyword=hot dog' },
        @{ path = '/api/items?primaryType=FOOD&keyword=%E8%8F%87'; label = 'primaryType + keyword 叠加' })) {
    $ignored = Invoke-Json 'GET' $case.path 60
    $ignoredCount = (As-Array (Get-Field (Get-Data $ignored) 'items')).Count
    Assert-Equal $ignoredCount 134 ($case.label + ' 被忽略：仍返回全量 134 条')
}

# ── 详情：路径参数是 slug 不是 id；data **直接就是条目对象** ──
#
# ⚠️ 契约 §2 明确 data 不再包一层 item：这里必须 Get-Data 剥掉信封。
# 早先的写法读的是 `$detail.Json.item` —— 那个键根本不存在，恒得 $null，
# 于是下面每一条断言全部被 `if ($null -ne $detailItem)` 挡在外面、**一条都不跑**。
# 这与 #27 踩的"少剥一层 data → 静默空转"是同一个坑的另一种发作方式。
Write-Host ''
Write-Host '  详情：'
$detail = Invoke-Json 'GET' '/api/items/hot_dog' 60
Assert-True ($detail.Ok -and $detail.Status -eq 200) '/api/items/hot_dog HTTP 200' `
    ("实际 status=" + $detail.Status + " error=" + $detail['Error'])
$detailItem = Get-Data $detail
Assert-True ($null -ne $detailItem) '/api/items/hot_dog 的 data 直接是条目对象（不包 item）' `
    ("实际 data=" + (Format-Actual $detailItem))
if ($null -ne $detailItem) {
    Assert-Equal (Get-Field $detailItem 'slug') 'hot_dog' '详情 slug=hot_dog'
    Assert-Equal (Get-Field $detailItem 'nameEn') 'Hot Dog' '详情 nameEn=Hot Dog'
    Assert-True ((Get-Field $detailItem 'isCookable') -eq $true) 'Hot Dog isCookable=true' `
        ("实际 " + (Format-Actual (Get-Field $detailItem 'isCookable')))

    # 字段面 = 契约 §2 的 12 个。英文 description 是**有意剔除**的（中文那份才是唯一描述字段），
    # 库里 id 也不下发 —— 多一个少一个都算契约漂移。
    # 判据用"键在不在"而不是"值非不非空"：achievement 可空（库里只 28 条有），键仍必须在。
    # Where-Object 过滤出来的标量在 StrictMode 下 .Count 不可靠，所以用显式 foreach 记账。
    $DETAIL_FIELDS = @('slug', 'nameZh', 'nameEn', 'icon', 'weight', 'primaryType', 'isCookable',
                       'raw', 'cooked', 'descriptionZh', 'achievement', 'tags')
    $detailMissing = @()
    foreach ($f in $DETAIL_FIELDS) {
        if ($null -eq $detailItem.PSObject.Properties[$f]) { $detailMissing += $f }
    }
    Assert-True ($detailMissing.Count -eq 0) ('详情带齐契约 §2 的 12 个字段（' + ($DETAIL_FIELDS -join ' / ') + '）') `
        ("缺字段：" + ($detailMissing -join ', '))
    Assert-True ($null -eq (Get-Field $detailItem 'description')) '详情不下发英文 description（只给 descriptionZh）' `
        ("实际 description=" + (Format-Actual (Get-Field $detailItem 'description')))

    # raw / cooked 两栏都要非空 —— 这是 autoResultMap 漏了的话会静默全 null 的回归保护（契约 §8.5）
    $rawEffects = As-Array (Get-Field $detailItem 'raw')
    $cookedEffects = As-Array (Get-Field $detailItem 'cooked')
    Assert-True ($rawEffects.Count -gt 0) 'Hot Dog 的 raw 非空' ("实际 " + $rawEffects.Count + " 条")
    Assert-True ($cookedEffects.Count -gt 0) 'Hot Dog 的 cooked 非空' ("实际 " + $cookedEffects.Count + " 条")

    # 元素五个字段，且 code 已剥掉 _COOKED 后缀（契约 §2.1）
    $badEffectShape = @()
    $stillSuffixed = @()
    foreach ($group in @($rawEffects, $cookedEffects)) {
        foreach ($e in $group) {
            foreach ($f in @('code', 'nameZh', 'value')) {
                if ($null -eq (Get-Field $e $f)) { $badEffectShape += (Get-Field $e 'code') + '.' + $f }
            }
            $c = [string](Get-Field $e 'code')
            if ($c.EndsWith('_COOKED')) { $stillSuffixed += $c }
        }
    }
    Assert-True ($badEffectShape.Count -eq 0) 'raw / cooked 元素都带 code / nameZh / value' `
        ("缺字段 " + $badEffectShape.Count + " 处：" + (($badEffectShape | Select-Object -First 5) -join ', '))
    Assert-True ($stillSuffixed.Count -eq 0) 'raw / cooked 的 code 已剥掉 _COOKED 后缀' `
        ("仍有后缀：" + (($stillSuffixed | Select-Object -First 5) -join ', '))

    # 已知答案（契约 §2 样例，已对库核对）：熟值是**总量**、不是增量
    $rawHunger = Get-EffectByCode $rawEffects 'HUNGER'
    Assert-Equal (Get-Field $rawHunger 'value') -30 'Hot Dog raw HUNGER=-30'
    $cookedHunger = Get-EffectByCode $cookedEffects 'HUNGER'
    Assert-Equal (Get-Field $cookedHunger 'value') -60 'Hot Dog cooked HUNGER=-60（熟食总量）'
    $cookedHungerName = [string](Get-Field $cookedHunger 'nameZh')
    Assert-True ($cookedHungerName.Length -gt 0) 'Hot Dog cooked HUNGER 带中文名（服务端效果字典）' `
        'cooked HUNGER 的 nameZh 是空的'

    # tags 是**全量维度**下发（契约 §2.2）：详情页的生态 / 来源 / 稀有度展示区靠它。
    # 注意不是"每个条目都恰好六个维度" —— 库里 location 只 18 条有，最多的条目也只有 5 维。
    # 这里钉的是"有的维度都带出来了"，取样用 Ancient Idol（它有 location）
    $detailDims = @()
    foreach ($t in (As-Array (Get-Field $detailItem 'tags'))) { $detailDims += (Get-Field $t 'code') }
    Assert-True ($detailDims -contains 'type') 'Hot Dog 详情的 tags 含 type 维度' ("实际 " + (($detailDims | Sort-Object -Unique) -join ', '))

    # descriptionZh 是唯一下发的描述，必须是中文且非空
    $dzh = [string](Get-Field $detailItem 'descriptionZh')
    Assert-True ($dzh.Length -gt 0) '详情带非空的 descriptionZh' '详情里 descriptionZh 是空的'
}

# tags 的六维覆盖：库里 location 只有 18 条有，取一条带 location 的确认它也下发了
$idol = Invoke-Json 'GET' '/api/items/ancient_idol' 60
$idolItem = Get-Data $idol
if ($null -ne $idolItem) {
    $idolDims = @()
    foreach ($t in (As-Array (Get-Field $idolItem 'tags'))) { $idolDims += (Get-Field $t 'code') }
    foreach ($d in @('type', 'biome', 'location')) {
        Assert-True ($idolDims -contains $d) ("Ancient Idol 详情的 tags 含 $d 维度（有该维度的条目要带出来）") `
            ("实际 " + (($idolDims | Sort-Object -Unique) -join ', '))
    }
} else {
    Add-Fail 'Ancient Idol 详情可取' '取不到 /api/items/ancient_idol'
}

# 不可烹饪：cooked 必须是**空数组**、isCookable=false，前端据此隐藏熟食那栏（契约 §2.1 / Q7）
$firstAidDetail = Invoke-Json 'GET' '/api/items/first_aid_kit' 60
$firstAidItem = Get-Data $firstAidDetail
Assert-True ($null -ne $firstAidItem) 'first_aid_kit 详情可取' ("实际 " + (Format-Actual $firstAidItem))
if ($null -ne $firstAidItem) {
    Assert-True ((Get-Field $firstAidItem 'isCookable') -eq $false) 'first_aid_kit isCookable=false' `
        ("实际 " + (Format-Actual (Get-Field $firstAidItem 'isCookable')))
    Assert-True ((As-Array (Get-Field $firstAidItem 'cooked')).Count -eq 0) 'first_aid_kit 的 cooked 是空数组（前端据此隐藏熟食栏）' `
        ("实际 " + (As-Array (Get-Field $firstAidItem 'cooked')).Count + " 条")
    Assert-True ((As-Array (Get-Field $firstAidItem 'raw')).Count -gt 0) 'first_aid_kit 的 raw 仍非空' `
        ("实际 " + (As-Array (Get-Field $firstAidItem 'raw')).Count + " 条")
}

# slug 里那批被改写过的字符要真的能取到：Bugle? 的 slug 是 bugle_（问号被换成下划线）
$bugleQuery = Invoke-Json 'GET' '/api/items/bugle_' 60
Assert-True ($null -ne (Get-Data $bugleQuery)) '被改写过的 slug（bugle_）也能取到详情' `
    '取不到 bugle_：slug 规则与 icon 文件名的字符白名单没对齐'

# ── 第 4 组（续）：登录与教学身份（契约 §6 / §7.1）──────────────────────────
#
# ⚠️ 必须插在 Redis 段**之前**（见下面 Redis 段开头的说明）：Redis 段会 DEL
# 'guide:item:all' 并假设库里是唯一数据源，那之后按库内容取的参考值可能取到空表。
#
# ⚠️ 这一组需要后端连着真微信服务器（code2Session）+ MySQL：
#   - appid / secret 没配（application-local.yml 缺 guide.wechat.*）→ code2Session 报错
#   - 网络不通 / 测试 js_code 无效 → 同样换不到 openid
#   这时候 login 拿不到 token，下面几条会 FAIL —— 是**真失败**不是脚本误报，
#   因为票面要求"登录拿到非空 openid 与 token"。
#   openid 是后端从微信换出来的，脚本造不出来，所以只能拿真 js_code 去换。
#
# ⚠️ 还要有 `user` 表（openid 主键 / level / api_key / create_time / nickname / avatar）。
#   仓库里没有建表 SQL，表结构的唯一真相是 pojo/User.java；换机器要照它手写 DDL。
#   表不存在时 login 会返 code=-100（MySQL 报 Table 'xxx.user' doesn't exist），
#   下面"拿到非空 token"那条会 FAIL —— 同样是真失败，先把表建出来。

# 未登录断言的公共形态：Shiro 的 tokenAuthc 在过滤链上把请求拦下，回 **HTTP 401**
# + 响应壳 code=401（不再是控制器里 HTTP 200 + code=401 那套）。
# ⚠️ PS 5.1 从异常里不一定取得到响应体（见 Invoke-Json），所以 **HTTP 状态是硬断言**，
# 响应壳只在真取到时加验一条，取不到记 INFO —— 不让取体失败变成一次假 FAIL。
function Assert-Unauthorized($resp, [string] $name) {
    Assert-True ($resp.Status -eq 401) ($name + ' HTTP 401（Shiro tokenAuthc 拦下）') `
        ("实际 status=" + $resp.Status + " error=" + $resp['Error'])
    $code = Get-Field $resp.Json 'code'
    if ($null -ne $code) {
        Assert-Equal $code 401 ($name + ' 响应壳里 code=401')
    } else {
        Add-Note ($name + ' 的 401 响应体在 PS 5.1 里取不到，只验了 HTTP 状态')
    }
}

# 路径门禁拦下：Shiro 的 roleAuthc 回 **HTTP 403** + 响应壳 code=403（与 401 同形，也是真 HTTP 状态）。
# 与 Assert-Unauthorized 同一套路：HTTP 状态硬断言，响应壳取到才加验，取不到记 INFO。
function Assert-Forbidden($resp, [string] $name) {
    Assert-True ($resp.Status -eq 403) ($name + ' HTTP 403（Shiro 路径门禁拦下）') `
        ("实际 status=" + $resp.Status + " error=" + $resp['Error'])
    $code = Get-Field $resp.Json 'code'
    if ($null -ne $code) {
        Assert-Equal $code 403 ($name + ' 响应壳里 code=403')
    } else {
        Add-Note ($name + ' 的 403 响应体在 PS 5.1 里取不到，只验了 HTTP 状态')
    }
}

Section '4b. 登录与教学身份'

$loginCode = 'smoke-test-' + [guid]::NewGuid().ToString('N')
$login = Invoke-Json 'POST' '/api/auth/login' 60 $null @{ js_code = $loginCode }
$loginData = Get-Data $login
$loginOpenid = Get-Field $loginData 'openid'
$loginToken  = Get-Field $loginData 'token'

Assert-True ($login.Ok -and $login.Status -eq 200) '登录接口 HTTP 200' `
    ("实际 status=" + $login.Status + " error=" + $login['Error'])
Assert-True ($null -ne $loginOpenid -and $loginOpenid -ne '') '登录拿到非空 openid' `
    'code2Session 没换到 openid：多半是 appid/secret 没配在 application-local.yml，或测试 js_code 无效'
Assert-True ($null -ne $loginToken -and $loginToken -ne '') '登录拿到非空 token（占位串，值即 openid）' `
    '契约 §6 要求 token 这个 key 必须存在，缺了前端会存进 undefined'

if ($null -eq $loginToken -or $loginToken -eq '') {
    # 没 token 后面的教学断言没有意义：直接说明并跳过，不静默空转。
    # 注意判 null 也要写上：login 失败时 data 是 null，Get-Field 返回的就是 $null，
    # 只写 `-eq ''` 的话 $null 穿不过去，下面会拿着空 token 去发请求。
    Write-Host '  登录没拿到 token，跳过身份条断言（先配好 guide.wechat.appid / secret 与 user 表再重跑）' -ForegroundColor Yellow
} else {
    # 无 token → Shiro tokenAuthc 拦下：HTTP 401（响应壳 code 也是 401）
    Assert-Unauthorized (Invoke-Json 'GET' '/api/teach/profile' 60) 'profile 不带 token 时'

    # 带 token → 200 + level / streak / streakTarget
    $profileResp = Invoke-Json 'GET' '/api/teach/profile' 60 @{ token = $loginToken }
    $profileData = Get-Data $profileResp
    Assert-True ((Get-Field $profileResp.Json 'code') -eq 200) 'profile 带 token 时 code=200' `
        ("实际 code=" + (Get-Field $profileResp.Json 'code') + " message=" + (Get-Field $profileResp.Json 'message'))
    Assert-True ($null -ne (Get-Field $profileData 'level')) 'profile 带 level' '契约 §7.1 要 level'
    Assert-True ($null -ne (Get-Field $profileData 'streak')) 'profile 带 streak' '契约 §7.1 要 streak'
    Assert-True ($null -ne (Get-Field $profileData 'streakTarget')) 'profile 带 streakTarget' '契约 §7.1 要 streakTarget'
}

# ── 第 4 组（续）：问答的 Key 与鉴权（契约 §7.2 / §7.4 / §7.5）───────────────
#
# ⚠️ 这一段**不碰大模型**，所以它不需要真 Key，也不需要 appid/secret 之外的任何外部依赖。
# 真调 DeepSeek 那一条（"填了 Key 后问图鉴问题拿到回答"）**脚本验不了** —— Key 是用户本人的、
# 不在仓库里，也没有任何接口能证明"这次调用真的花了那个 Key 的额度"。
# 那条 AC 的验收方式写在报告里，不在这个脚本里假装跑过。
#
# ⚠️ 仍然按 token 分支：拿不到 token（appid/secret 没配）时这一整段跳过，不静默空转。
Section '4c. 教学问答的 Key 与鉴权'

# 未登录时端点必须 401 —— 由 Shiro 的 tokenAuthc 在过滤链上拦下（HTTP 401 + 响应壳 code=401）
Assert-Unauthorized (Invoke-Json 'POST' '/api/teach/ask' 60 $null @{ question = 'probe' }) 'ask 不带 token 时'
Assert-Unauthorized (Invoke-Json 'POST' '/api/teach/apikey' 60 $null @{ apiKey = 'sk-probe' }) 'apikey 不带 token 时'

if ($null -eq $loginToken -or $loginToken -eq '') {
    Write-Host '  登录没拿到 token，跳过问答的鉴权断言（先配好 guide.wechat.appid / secret 与 user 表再重跑）' -ForegroundColor Yellow
} else {
    # 带 token 但没填 Key：必须是 -100 + 提示先填，**不是 500**（契约 §7.5）
    # 这一步顺带证明了"claim 了的 Key 才能问"这条路径是通的，且没有真去调大模型
    $authHeader = @{ token = $loginToken }
    $askNoKey = Invoke-Json 'POST' '/api/teach/ask' 60 $authHeader @{ question = '蘑菇有什么用' }
    Assert-True ((Get-Field $askNoKey.Json 'code') -eq -100) 'ask 未填 Key 时 code=-100（不是 500）' `
        ("实际 code=" + (Get-Field $askNoKey.Json 'code') + " message=" + (Get-Field $askNoKey.Json 'message'))
    Assert-True ($null -ne (Get-Field $askNoKey.Json 'message') -and (Get-Field $askNoKey.Json 'message') -ne '') `
        'ask 未填 Key 时带非空 message（前端 reject 分支直接弹它）' `
        ("实际 message=" + (Get-Field $askNoKey.Json 'message'))
    # 失败分支没有 data：前端靠 success=false 判成败，不该给出半个 answer
    Assert-True ($null -eq (Get-Field $askNoKey.Json 'data')) 'ask 失败时 data 为 null' `
        ("实际 data=" + (Format-Actual (Get-Field $askNoKey.Json 'data')))

    # 空问题也是 -100 + 提示，不能是 500
    $askEmpty = Invoke-Json 'POST' '/api/teach/ask' 60 $authHeader @{ question = '' }
    Assert-True ((Get-Field $askEmpty.Json 'code') -eq -100) 'ask 空问题时 code=-100（不是 500）' `
        ("实际 code=" + (Get-Field $askEmpty.Json 'code'))

    # 不预校验（契约 §7.2）：随便填一个明显无效的 Key 也要 200，有效性留到真调用时才知道。
    # 这一条是在**钉行为**而不是走过场 —— 预校验会把"用户先填后充值"这种正常路径变成死路
    $saveKey = Invoke-Json 'POST' '/api/teach/apikey' 60 $authHeader @{ apiKey = 'sk-smoke-test-not-a-real-key' }
    Assert-True ((Get-Field $saveKey.Json 'code') -eq 200) 'apikey 填任意值都回 200（不预校验）' `
        ("实际 code=" + (Get-Field $saveKey.Json 'code') + " message=" + (Get-Field $saveKey.Json 'message'))

    # 存下之后身份条要能看见（契约 §7.1 的 hasApiKey 是前端的引导开关）
    $profileAfterKey = Invoke-Json 'GET' '/api/teach/profile' 60 $authHeader
    Assert-True ((Get-Field (Get-Data $profileAfterKey) 'hasApiKey') -eq $true) `
        '存了 Key 之后 profile 的 hasApiKey=true（前端据此收起填写引导）' `
        ("实际 hasApiKey=" + (Format-Actual (Get-Field (Get-Data $profileAfterKey) 'hasApiKey')))

    # ⚠️ 超时给足：这一步真的会拿那个无效 Key 去调 DeepSeek，拿到 401 才算对。
    # 网络不通时它会走"AI 服务连不上"那条兜底，同样是 -100 —— 两种都算这一条通过，
    # 因为这一条验的是"大模型的失败不会变成 500"，不是"网络一定通"
    $askBadKey = Invoke-Json 'POST' '/api/teach/ask' 90 $authHeader @{ question = 'smoke-test-bad-key-probe' }
    Assert-True ((Get-Field $askBadKey.Json 'code') -eq -100) 'ask 用无效 Key 时 code=-100（不是 500）' `
        ("实际 code=" + (Get-Field $askBadKey.Json 'code') + " message=" + (Get-Field $askBadKey.Json 'message'))
    Assert-True ((Get-Field $askBadKey.Json 'success') -eq $false) 'ask 用无效 Key 时 success=false' `
        ("实际 success=" + (Get-Field $askBadKey.Json 'success'))
}

# ── 第 4 组（续）：分级入口的路径门禁（契约 §7.0 / §7.7）──────────────────────
#
# ⚠️ 必须在**登录取到的这个新用户还是菜鸟（level 0）时**验：login 用的是每次新生成的
# js_code，findOrCreateByOpenid 建出来的就是 level 0。§4e 连对升级、§4f 顶到高手之后
# 就再也造不出"菜鸟调高级入口"这个场景了（dev/level 只能升不能降）。
#
# 落在 Shiro 过滤链上的 roleAuthc 拦下时回**真 HTTP 403**（不是响应包 code），所以断言看 Status。
Section '4c-2. 分级入口的路径门禁'

if ($null -eq $loginToken -or $loginToken -eq '') {
    Write-Host '  登录没拿到 token，跳过路径门禁断言（先配好 guide.wechat.appid / secret 与 user 表再重跑）' -ForegroundColor Yellow
} else {
    $tierHeader = @{ token = $loginToken }
    # 菜鸟 → beginner/route（要 ≥入门）→ 403
    Assert-Forbidden (Invoke-Json 'POST' '/api/teach/beginner/route' 60 $tierHeader) '菜鸟调 beginner/route 时'
    # 菜鸟 → expert/speedrun（要高手）→ 403
    Assert-Forbidden (Invoke-Json 'POST' '/api/teach/expert/speedrun' 60 $tierHeader @{ question = '怎么速通' }) `
        '菜鸟调 expert/speedrun 时'
}

# ── 第 4 组（续）：个人页昵称（契约 §7.1）────────────────────────────────────
#
# ⚠️ 昵称里塞的是 ASCII（PS 5.1 中文解码坑），所以可以直接精确比对。
# ⚠️ 头像上传（§7.8）不在这里验：PS 5.1 造 multipart 得手拼 body，不划算 ——
#    "缺 file 回 -200 不是 500"由单测 TeachControllerTest 钉住。
Section '4c-3. 个人资料（昵称）'

if ($null -eq $loginToken -or $loginToken -eq '') {
    Write-Host '  登录没拿到 token，跳过个人资料断言（先配好 guide.wechat.appid / secret 与 user 表再重跑）' -ForegroundColor Yellow
} else {
    $profileHeader = @{ token = $loginToken }
    $smokeNick = 'smoke-' + [guid]::NewGuid().ToString('N').Substring(0, 8)

    $saveProfile = Invoke-Json 'POST' '/api/teach/profile' 60 $profileHeader @{ nickname = $smokeNick }
    Assert-True ((Get-Field $saveProfile.Json 'code') -eq 200) 'POST /api/teach/profile 存昵称回 200' `
        ("实际 code=" + (Get-Field $saveProfile.Json 'code') + " message=" + (Get-Field $saveProfile.Json 'message'))

    $profileAfter = Invoke-Json 'GET' '/api/teach/profile' 60 $profileHeader
    Assert-Equal (Get-Field (Get-Data $profileAfter) 'nickname') $smokeNick 'GET profile 回读到刚存的昵称'

    # 昵称头像都空 → 参数错误，不是 500
    $emptyProfile = Invoke-Json 'POST' '/api/teach/profile' 60 $profileHeader @{ nickname = ''; avatar = '' }
    Assert-True ((Get-Field $emptyProfile.Json 'code') -eq -200) '昵称头像都空时 code=-200（不是 500）' `
        ("实际 code=" + (Get-Field $emptyProfile.Json 'code'))
}

# ── 第 4 组（续）：越级门禁（契约 §7.0 / #43）────────────────────────────────
#
# ⚠️ 只断言 ASCII 字段（code / success，以及 message 非空），**不比对中文文案** ——
# 响应头不带 charset，PS 5.1 按 ISO-8859-1 解码，中文 message 读出来是乱码（见文件头说明）。
# 文案选取由单测 TeachGateTest 逐个钉死，这里只做"越级确实被拦"的回归保护。
#
# ⚠️ 门禁按**当前等级**触发：dev/level 只能升不能降，所以按 profile 的 level 挑一个该等级
# 一定被拦的类别（菜鸟禁路线、入门禁速通）。已是高手时无从触发，记 INFO 跳过。
# 本段排在练习段**之前**：练习会答对 10 题升一级，放后面就把等级抬走了。
Section '4d. 越级门禁'

if ($null -eq $loginToken -or $loginToken -eq '') {
    Write-Host '  登录没拿到 token，跳过越级门禁断言（先配好 guide.wechat.appid / secret 与 user 表再重跑）' -ForegroundColor Yellow
} else {
    $gateHeader = @{ token = $loginToken }
    $gateLevel = [int](Get-Field (Get-Data (Invoke-Json 'GET' '/api/teach/profile' 60 $gateHeader)) 'level')
    $gateQuestion = $null
    if ($gateLevel -eq 0) { $gateQuestion = '今日最佳路线' }    # 菜鸟禁路线类
    elseif ($gateLevel -eq 1) { $gateQuestion = '怎么速通' }     # 入门禁速通类

    if ($null -eq $gateQuestion) {
        Add-Note ('当前等级 ' + $gateLevel + ' 是高手，越级门禁无从触发（把 user.level 置 0 可重验）')
    } else {
        # 越级：内容门禁拦在服务之前，不查回答缓存、不调大模型（票面 AC）。
        # 响应码自门禁整合起是 403（取 Shiro 那套语义），HTTP 仍 200（统一响应包惯例）。
        $blockedAsk = Invoke-Json 'POST' '/api/teach/ask' 60 $gateHeader @{ question = $gateQuestion }
        Assert-True ((Get-Field $blockedAsk.Json 'code') -eq 403) ('越级问题被拦时 code=403（等级 ' + $gateLevel + '）') `
            ("实际 code=" + (Get-Field $blockedAsk.Json 'code') + " message=" + (Get-Field $blockedAsk.Json 'message'))
        Assert-True ((Get-Field $blockedAsk.Json 'success') -eq $false) '越级问题 success=false' `
            ("实际 success=" + (Get-Field $blockedAsk.Json 'success'))
        Assert-True ($null -ne (Get-Field $blockedAsk.Json 'message') -and (Get-Field $blockedAsk.Json 'message') -ne '') `
            '越级问题带非空 message（文案内容不比对，只验有一句提示）' ''
    }
}

# ── 第 4 组（续）：练习抽题与判题（契约 §7.3）────────────────────────────────
#
# ⚠️ 这一段**不真调 DeepSeek**：练习题库是懒生成的（首次要某级题时才生成），
# 本段先用 redis-cli 往**当前等级对应的题库 key** 里塞一份已知题库，再走 next / answer。
# 这样既不需要真 Key，也能把"next 不吐答案"与"连对 10 题升级"两条 AC 钉死。
# 塞不进去（Redis / redis-cli 不可达）就跳过并记 INFO，不误报。
#
# ⚠️ 题库 JSON 用 ASCII 题干（不含中文），值从 stdin（-x）送进去 —— 避开 PS 5.1
# 传"含双引号的原生参数"时的转义地狱，也避开中文按 ANSI 编码的坑。
Section '4e. 练习抽题与判题'

if ($null -eq $loginToken -or $loginToken -eq '') {
    Write-Host '  登录没拿到 token，跳过练习断言（先配好 guide.wechat.appid / secret 与 user 表再重跑）' -ForegroundColor Yellow
} else {
    # 已知题库：30 题，第 i 题的正确项是 i % 4 —— 判题规则固定，脚本据此答对。
    # 题库 key 按**当前等级**取，不写死 0：脚本可重复跑，用户已经升过级也不会错位。
    $quizHeader = @{ token = $loginToken }
    $quizProfile = Get-Data (Invoke-Json 'GET' '/api/teach/profile' 60 $quizHeader)
    $startLevel = [int](Get-Field $quizProfile 'level')
    $QUIZ_BANK_KEY = 'guide:quiz:bank:' + $startLevel
    $progressKey = 'guide:quiz:progress:' + $loginOpenid

    $quizItems = @()
    for ($i = 0; $i -lt 30; $i++) {
        $quizItems += ('{"id":' + $i + ',"stem":"Q' + $i + '","options":["A","B","C","D"],"answerIndex":' + ($i % 4) + ',"explanation":"E' + $i + '"}')
    }
    $quizBankJson = '[' + ($quizItems -join ',') + ']'
    $quizSet = $quizBankJson | & $script:RedisCli -x SET $QUIZ_BANK_KEY 2>&1
    $quizRedisOk = ($LASTEXITCODE -eq 0) -and (("$quizSet").Trim() -eq 'OK')

    if (-not $quizRedisOk) {
        Add-Note ('Redis 不可达或 redis-cli 不可用，跳过练习断言（redis-cli 返回：' + "$quizSet" + '）')
    } else {
        # 先清掉这个用户的连对进度，保证从"连对 0、无排除集"开始，脚本可重复跑
        $null = & $script:RedisCli DEL $progressKey 2>&1

        # 抽题：响应只有题号 / 题干 / 选项，**没有答案**（票面 AC）
        $quizNext = Invoke-Json 'POST' '/api/teach/quiz/next' 60 $quizHeader
        Assert-True ((Get-Field $quizNext.Json 'code') -eq 200) 'quiz/next code=200' `
            ("实际 code=" + (Get-Field $quizNext.Json 'code') + " message=" + (Get-Field $quizNext.Json 'message'))
        $quizNextData = Get-Data $quizNext
        Assert-True ($null -ne (Get-Field $quizNextData 'questionId')) 'quiz/next 带题号 questionId' '响应里没有 questionId'
        Assert-True ($null -ne (Get-Field $quizNextData 'stem') -and (Get-Field $quizNextData 'stem') -ne '') 'quiz/next 带题干 stem' '响应里没有 stem'
        $quizOptions = As-Array (Get-Field $quizNextData 'options')
        Assert-Equal $quizOptions.Count 4 'quiz/next 带四个选项'

        # 响应里**绝不能**有答案字段：抓包就能看到它，下发答案等于把连对白送
        $quizLeaked = @()
        foreach ($f in @('correctIndex', 'explanation', 'correct', 'answer')) {
            if ($null -ne (Get-Field $quizNextData $f)) { $quizLeaked += $f }
        }
        Assert-True ($quizLeaked.Count -eq 0) 'quiz/next 响应不含任何答案字段（correctIndex / explanation / correct）' `
            ('多带了：' + ($quizLeaked -join ', '))
        $quizKeys = @($quizNextData.PSObject.Properties | ForEach-Object { $_.Name })
        Assert-Equal $quizKeys.Count 3 'quiz/next 只有题号 / 题干 / 选项三个键' ('实际键：' + ($quizKeys -join ', '))

        # 重置练习（契约 §7.3）：先答对一题把连对顶上 1，再重置，看它是否归零。
        # 重置只清进度、不退等级 —— 所以这里只断 streak，不断 level。
        $beforeReset = Get-Data (Invoke-Json 'POST' '/api/teach/quiz/next' 60 $quizHeader)
        $beforeResetId = [int](Get-Field $beforeReset 'questionId')
        $null = Invoke-Json 'POST' '/api/teach/quiz/answer' 60 $quizHeader @{ questionId = $beforeResetId; choice = ($beforeResetId % 4) }
        $streakBeforeReset = Get-Field (Get-Data (Invoke-Json 'GET' '/api/teach/profile' 60 $quizHeader)) 'streak'
        Assert-Equal $streakBeforeReset 1 '答对一题后 streak=1（重置断言的前提）'

        $resetResp = Invoke-Json 'POST' '/api/teach/quiz/reset' 60 $quizHeader
        Assert-True ((Get-Field $resetResp.Json 'code') -eq 200) 'quiz/reset 回 200' `
            ("实际 code=" + (Get-Field $resetResp.Json 'code') + " message=" + (Get-Field $resetResp.Json 'message'))
        $streakAfterReset = Get-Field (Get-Data (Invoke-Json 'GET' '/api/teach/profile' 60 $quizHeader)) 'streak'
        Assert-Equal $streakAfterReset 0 '重置后 streak 归零'

        # 选题库档位（契约 §7.3）：只能 ≤ 自己等级（单调包含）。越档回 403，
        # 而且是在碰题库之前就拒 —— 所以不需要那一档的题库存在也能验。
        $lockedBank = Invoke-Json 'POST' '/api/teach/quiz/next' 60 $quizHeader @{ level = ($startLevel + 1) }
        Assert-True ((Get-Field $lockedBank.Json 'code') -eq 403) ('选高于自己等级的档位回 403（当前 ' + $startLevel + '）') `
            ("实际 code=" + (Get-Field $lockedBank.Json 'code') + " message=" + (Get-Field $lockedBank.Json 'message'))

        # 连答 10 题正确（题 i 的正确项是 i % 4）
        $quizFailed = $false
        $lastAnswer = $null
        for ($n = 1; $n -le 10; $n++) {
            $q = Get-Data (Invoke-Json 'POST' '/api/teach/quiz/next' 60 $quizHeader)
            $qId = Get-Field $q 'questionId'
            $correctIndex = ([int]$qId) % 4
            $ans = Get-Data (Invoke-Json 'POST' '/api/teach/quiz/answer' 60 $quizHeader @{ questionId = $qId; choice = $correctIndex })
            if ((Get-Field $ans 'correct') -ne $true) {
                $quizFailed = $true
                Add-Fail ('第 ' + $n + ' 题按已知答案作答却判错') ('id=' + $qId + ' 正确项=' + $correctIndex + ' 实际 correct=' + (Format-Actual (Get-Field $ans 'correct')))
                break
            }
            $lastAnswer = $ans
        }
        if (-not $quizFailed) {
            # 契约 §7.3：判题响应自带 correctIndex / streak / level / upgraded，前端据此就地刷身份条
            foreach ($f in @('correctIndex', 'streak', 'level', 'upgraded')) {
                Assert-True ($null -ne (Get-Field $lastAnswer $f)) ('quiz/answer 带 ' + $f) ('响应里没有 ' + $f)
            }
            # 题型 AC：等级涨 1、连对归零（身份条是权威，再用 profile 复核一份）
            $expectedLevel = [Math]::Min($startLevel + 1, 2)
            $profileAfterQuiz = Get-Data (Invoke-Json 'GET' '/api/teach/profile' 60 $quizHeader)
            Assert-Equal (Get-Field $profileAfterQuiz 'level') $expectedLevel ('连对满 10 后 level = ' + $expectedLevel)
            Assert-Equal (Get-Field $profileAfterQuiz 'streak') 0 '连对满 10 后 streak 归零'
        }

        # Redis：题库 key 在；连对 key 在且 TTL ≤ 2 小时（票面 AC）
        $bankExists = & $script:RedisCli EXISTS $QUIZ_BANK_KEY 2>&1
        Assert-Equal (("$bankExists").Trim()) '1' ('Redis 里有题库 key（' + $QUIZ_BANK_KEY + '）')
        $progressTtl = & $script:RedisCli TTL $progressKey 2>&1
        $ttlOk = $false
        try { $ttlOk = (([int]$progressTtl) -gt 0) -and (([int]$progressTtl) -le 7200) } catch { $ttlOk = $false }
        Assert-True $ttlOk '连对 key 存在且 TTL ≤ 2 小时' ('实际 TTL=' + $progressTtl)

        # 收尾：清掉脚本自己塞的题库与连对 key（真实缓存由采集失效）
        $null = & $script:RedisCli DEL $QUIZ_BANK_KEY $progressKey 2>&1
    }
}

# ── 第 4 组（续）：每日路线（契约 §7.4 / #44）────────────────────────────────
#
# ⚠️ 前端把「今日路线」只开给高手，所以这里先 dev/level 顶到 2；端点本身要 ≥入门（§7.0）。
# dev/level 只能升不能降、封顶 2，叫两次一定到 2（无论当前是 0 还是 1）。它与 testInsertUser 同类，是演示后门。
#
# ⚠️ 这一条**不依赖大模型、也不依赖用户的 Key** —— 链接由后端构造
# （模型不能联网，CONTEXT.md「每日路线」）。第一级真会去打 api.bilibili.com；即便被风控挡下，
# 也会降级到空间/全站搜索页，仍然是 bilibili.com 域，所以这一条**联网不可用时依然成立**。
#
# ⚠️ 中文 title 不比对（响应头不带 charset，PS 5.1 读出来是乱码，见文件头说明），只验非空与域名。
Section '4f. 每日路线'

if ($null -eq $loginToken -or $loginToken -eq '') {
    Write-Host '  登录没拿到 token，跳过路线断言（先配好 guide.wechat.appid / secret 与 user 表再重跑）' -ForegroundColor Yellow
} else {
    $routeHeader = @{ token = $loginToken }
    # 顶到高手：叫一次可能停在 1，叫第二次一定到 2（封顶也是 2）
    $null = Invoke-Json 'POST' '/api/teach/dev/level' 60 $routeHeader
    $routeLevel = [int](Get-Field (Get-Data (Invoke-Json 'POST' '/api/teach/dev/level' 60 $routeHeader)) 'level')
    Assert-Equal $routeLevel 2 'dev/level 把等级顶到高手（路线断言的前提）'

    # 高手走分级入口（§7.7）：Shiro 的 beginner/** 规则放行，后端构造链接、不调大模型
    $route = Invoke-Json 'POST' '/api/teach/beginner/route' 90 $routeHeader
    Assert-True ((Get-Field $route.Json 'code') -eq 200) '高手走 beginner/route 拿路线 code=200' `
        ("实际 code=" + (Get-Field $route.Json 'code') + " message=" + (Get-Field $route.Json 'message'))

    $routeData = Get-Data $route
    $routeLinks = As-Array (Get-Field $routeData 'links')
    Assert-True ($routeLinks.Count -gt 0) '路线回答带非空 links' ("实际 " + $routeLinks.Count + " 条")

    $badRouteUrl = @()
    $badRouteShape = @()
    foreach ($l in $routeLinks) {
        $u = [string](Get-Field $l 'url')
        $t = [string](Get-Field $l 'title')
        # title 后端给的是中文短语（含日期），只验非空；域名是 ASCII，可以精确验
        if ([string]::IsNullOrWhiteSpace($t)) { $badRouteShape += '(title 为空)' }
        if ([string]::IsNullOrWhiteSpace($u) -or ($u -notlike '*bilibili.com*')) { $badRouteUrl += $u }
    }
    Assert-True ($badRouteShape.Count -eq 0) '每条路线链接都带非空 title' ('缺 title ' + $badRouteShape.Count + ' 处')
    Assert-True ($badRouteUrl.Count -eq 0) '每条路线链接都是 bilibili.com 域' `
        ('非 bilibili 域：' + (($badRouteUrl | Select-Object -First 3) -join ', '))
}

# ── Redis 缓存：读到的到底是缓存、还是每次都回源 ─────────────────────────────
#
# ⚠️ 这一段**不要求 Redis 一定在跑**：契约 §8.3 明写"Redis 连不上时接口照常返（回源 MySQL）"，
# 所以下面每一条都在两种状态下成立 —— 有 Redis 时验"缓存真的被用上了"，
# 没有 Redis 时验"降级路径没把接口带崩"。脚本不因为 Redis 不在就变红。
#
# ⚠️ 为什么专门验"删 key 再请求"，而不是"验响应里有缓存标记"：
# 缓存有没有生效**从接口响应上完全看不出来**（这正是它的设计意图 —— 契约 §0.1 不变）。
# 所以在服务端加标记会污染契约，"关掉 Redis 接口照常返"是另一个代理能代跑的活。
# 剩下唯一能在这里钉死的，就是**单 key 的语义本身**：契约 §8.3 规定图鉴全量只占
# `guide:item:all` 一个 key、存 JSON 数组、无 TTL —— 这三条都能从 HTTP 外壳上观察。
#
# ⚠️ 这套断言是**为 #28 并行开发准备的**：它要求 DEL 之后 MySQL 是唯一数据源，
# 所以必须排在**所有按库内容取的断言之后**（这里已经是第 4 组的尾部，
# 下一位改这个脚本时请把新断言加在这一段**之前**，否则参考值可能取到空表）。
#
# ⚠️ AI 教学（`/api/teach/**`）那一组新断言就插在这一行之上 —— 即第 4 组内、Redis 段之前。
# 教学接口按 token 认人、按等级抽题，取的是库里的 user 行与题库；落到 Redis 段后面的话，
# 前面的 DEL 'guide:item:all' 已经把图鉴缓存清空过一次，参考值可能取到空表。
# 教学端点用 Invoke-Json 的 $Headers / $Body 两个参数发（POST + token 头 + JSON body），
# 用法见 Invoke-Json 上方注释。
Write-Host ''
Write-Host '  缓存（单 key 语义）：'
$CACHE_KEY_PATH = '/cache/item-all'
$cacheBefore = Invoke-Json 'GET' $CACHE_KEY_PATH 60
# ⚠️ 必须剥统一响应包的 data 一层（Get-Data）：信封里只有 success / code / message / data，
# 直接取 `$cacheBefore.Json.reachable` 恒得 $null → 判成"Redis 不可达"→ 整组断言**静默跳过**。
# 这与前面列表 / 字典那几处踩的是同一个坑（见 Get-Data 上方）。
$cacheBeforeData = Get-Data $cacheBefore
$cacheReachable = $cacheBefore.Ok -and (Get-Field $cacheBeforeData 'reachable') -eq $true

if (-not $cacheReachable) {
    # 不算通过也不算失败：脚本要能在"只起了后端、没起 Redis"的机器上跑。
    # 这条路径的验收靠"停掉 Redis 再启动应用"那一组（见票面），不在这里重复。
    Add-Note ('Redis 不可达，跳过缓存单 key 断言（接口照常走回源 MySQL）')
} else {
    $cacheKey   = Get-Field $cacheBeforeData 'key'
    $cacheTtl   = Get-Field $cacheBeforeData 'ttl'
    $cacheCount = Get-Field $cacheBeforeData 'count'
    $cacheBytes = Get-Field $cacheBeforeData 'bytes'

    # key 名是契约 §8.3 的字面量：拆主类型会让跨界条目一份数据两处存、拆组合则 key 爆炸
    Assert-Equal $cacheKey 'guide:item:all' '图鉴全量只占 guide:item:all 一个 key'
    # 无 TTL：ttl = -1 就是"没有过期时间"（-2 是"key 不存在"）
    Assert-Equal $cacheTtl -1 'key 不设过期时间（无 TTL）'
    # 缓存里就是全量：与 /api/items 的条数一致
    Assert-Equal $cacheCount $allItems.Count '缓存里的条数 = /api/items 的条数（全量都灌进去了）'
    Assert-GreaterOrEqual $cacheBytes 1000 '缓存里存的是条目 JSON（不是空串）'

    # 手动删掉 key，下一次请求必须自动回填，且结果与删之前**一字不差** ——
    # 这一条验的是"回源"真的接上了：删完之后没有任何东西可读，还能返回同样这些条目，
    # 只能是从 MySQL 重新取的。比对用 JSON 文本而不是只比条数：
    # 条数只证明"有 134 条"，证明不了"回填回来的就是刚才那 134 条"
    $beforeEvictJson = $all.Json | ConvertTo-Json -Depth 10 -Compress

    # 删 key 走 redis-cli，不再打那个 POST /cache/item-all/evict 端点 ——
    # 那个端点是破坏性动作却落在全放行的 `/** = anon` 下（Shiro 收口方案只覆盖
    # `/admin/**`，不覆盖 `/cache/**`，所以它不会"以后自然收口"），已停用映射。
    # 验收本来就是"看 key 在不在、删掉再看回填"，redis-cli 两条命令的事，
    # 不需要一个常驻接口代劳。连接用 Spring Boot 默认值 127.0.0.1:6379
    # （两份 yml 都没有配 spring.data.redis.host/port，实现就是靠默认值连上的；
    # application.yml 里只多了 timeout / connect-timeout 两个超时项，不改变连哪台）。
    $redisDel = & $script:RedisCli DEL 'guide:item:all' 2>&1
    $redisDelOk = ($LASTEXITCODE -eq 0) -and (("$redisDel").Trim() -eq '1')
    Assert-True $redisDelOk '删 key 成功（模拟 Redis 被清空）' `
        ("redis-cli 返回：`"$redisDel`"（期望 1，exit=$LASTEXITCODE）；RedisCli = " + $script:RedisCli)

    $afterEvict = Invoke-Json 'GET' '/api/items' 60
    $afterEvictItems = As-Array (Get-Field (Get-Data $afterEvict) 'items')
    Assert-Equal $afterEvictItems.Count 134 '删 key 后仍返回全量 134 条（回源 MySQL，不是空表）'
    Assert-True (($afterEvict.Json | ConvertTo-Json -Depth 10 -Compress) -eq $beforeEvictJson) `
        '回源的结果与删 key 之前一字不差' '删 key 前后两次 /api/items 的响应体不同'

    # 回填：再问一次缓存组件，key 应当又在了，且条数仍是全量
    $cacheAfter = Invoke-Json 'GET' $CACHE_KEY_PATH 60
    Assert-Equal (Get-Field (Get-Data $cacheAfter) 'count') $afterEvictItems.Count '下一次请求自动回填，条数与刚取回的一致'

    # JSON 列往返（本票最容易出错的地方）：回源的这份要能正常拆出生熟两栏，
    # 说明从缓存读回来的 tag / effect 仍是实体、没有退化成 Map
    $afterEvictDetail = Invoke-Json 'GET' '/api/items/hot_dog' 60
    $afterEvictDetailItem = Get-Data $afterEvictDetail
    Assert-True ((As-Array (Get-Field $afterEvictDetailItem 'raw')).Count -gt 0) `
        '回填后的缓存仍能正确拆出 raw（JSON 列往返没退化成 Map）' `
        ("实际 " + (As-Array (Get-Field $afterEvictDetailItem 'raw')).Count + " 条")
    Assert-True ((Get-Field $afterEvictDetailItem 'isCookable') -eq $true) `
        '回填后的缓存里 flag=cookable 标签仍能判出来（tags 元素没退化成 Map）' `
        ("实际 " + (Format-Actual (Get-Field $afterEvictDetailItem 'isCookable')))

    # ── AI 问答缓存（契约 §7.4）────────────────────────────────────────────
    #
    # ⚠️ 这一段在 Redis 段**内部**，用的是上面刚验过的"Redis 可达"这个前提 ——
    # 缓存有没有生效从接口响应上完全看不出来（那是设计意图），所以按票面口径一律走 redis-cli，
    # **不新增 HTTP 检查端点**（上一个正是因此被停用）。
    #
    # ⚠️ 这里**只验 key 的存在与形态**，不验"第二次不再调大模型"：后者要有真 Key 才能造出
    # "第一次真的成功了"这个前提。本段的做法是自己往 key 里塞一条回答，再确认它落在
    # 契约约定的前缀下、且能原样读回 —— 钉的是 key 口径，不是调用次数（调用次数由
    # TeachServiceImplTest 的单测覆盖，那里能精确断言"只调了一次"）。
    $ANSWER_PREFIX = 'guide:answer:'
    $answerProbeKey = $ANSWER_PREFIX + 'smoke-test-probe'

    $probeSet = & $script:RedisCli SET $answerProbeKey 'probe' 2>&1
    Assert-True (($LASTEXITCODE -eq 0) -and (("$probeSet").Trim() -eq 'OK')) `
        ('能往 ' + $ANSWER_PREFIX + '* 下写 key（前缀就是契约 §7.4 的口径）') `
        ("redis-cli 返回：`"$probeSet`"（期望 OK，exit=$LASTEXITCODE）")

    $probeGet = & $script:RedisCli GET $answerProbeKey 2>&1
    Assert-Equal (("$probeGet").Trim()) 'probe' '回答缓存能原样读回（不是写进去就丢）'

    # 收掉探针：它是脚本自己造的，留着会污染下次运行的观察（真回答缓存的清理走采集失效）
    $null = & $script:RedisCli DEL $answerProbeKey 2>&1

    Write-Host ''
    Write-Host '  缓存单 key 断言完成 —— 下面轮到字典组（不依赖库内容，也不依赖缓存里还有没有东西）'
}

# 不存在的 slug 要走统一响应包的失败分支，不是 500、也不是 200 带 data:null（契约 §2.3）
$missing = Invoke-Json 'GET' '/api/items/does_not_exist' 60
Assert-True ($missing.Ok -and (Get-Field $missing.Json 'success') -eq $false -and (Get-Field $missing.Json 'code') -eq -100) `
    '不存在的 slug 返回 success=false / code=-100（不是 200 带 data:null）' `
    ("实际 status=" + $missing.Status + " json=" + (Format-Actual $missing.Json))

# ── 标签字典与生态 ──
#
# ⚠️ 取字段必须剥 data 这一层（Get-Data）：信封里没有 tags / biomes，
# 直接取 `$tags.Json.tags` 恒得 $null → As-Array 成空数组 → 逐条断言一条都不进、静默全 PASS。
# 上面 Get-Data 的注释里记的就是这个坑。
Write-Host ''
Write-Host '  字典：'
$tags = Invoke-Json 'GET' '/api/tags' 60
Assert-True ($tags.Ok -and $tags.Status -eq 200) '/api/tags HTTP 200' `
    ("实际 status=" + $tags.Status + " error=" + $tags['Error'])
$tagList = As-Array (Get-Field (Get-Data $tags) 'tags')

# 字典是静态的，条目数就是 TagDictionary 的定稿清单：type 12 / biome 11 / rarity 7 / source 19 / location 9 / flag 2
Assert-Equal $tagList.Count 60 '/api/tags 返回字典全量 60 条（六维定稿清单）'

# 六个维度一个都不能少；每个元素都必须带非空 code / value / nameZh
$dimensions = @()
$badTagElement = @()
foreach ($t in $tagList) {
    $dimensions += (Get-Field $t 'code')
    foreach ($f in @('code', 'value', 'nameZh')) {
        $v = [string](Get-Field $t $f)
        if ([string]::IsNullOrWhiteSpace($v)) { $badTagElement += ((Get-Field $t 'code') + '/' + (Get-Field $t 'value') + '.' + $f) }
    }
}
foreach ($d in @('type', 'biome', 'rarity', 'source', 'location', 'flag')) {
    Assert-True ($dimensions -contains $d) ("/api/tags 含 $d 维度") ("实际维度：" + (($dimensions | Sort-Object -Unique) -join ', '))
}
Assert-True ($badTagElement.Count -eq 0) '每个标签元素都带非空 code / value / nameZh（字典里的取值都有中文名）' `
    ("有 " + $badTagElement.Count + " 处缺字段：" + (($badTagElement | Select-Object -First 5) -join ', '))

$biomes = Invoke-Json 'GET' '/api/biomes' 60
Assert-True ($biomes.Ok -and $biomes.Status -eq 200) '/api/biomes HTTP 200' `
    ("实际 status=" + $biomes.Status + " error=" + $biomes['Error'])
$biomeList = As-Array (Get-Field (Get-Data $biomes) 'biomes')
Assert-Equal $biomeList.Count 11 '/api/biomes 返回 11 个生态'

$badBiome = @()
$biomeExtra = @()
foreach ($b in $biomeList) {
    if ([string]::IsNullOrWhiteSpace([string](Get-Field $b 'value')))   { $badBiome += '(value 为空)' }
    if ([string]::IsNullOrWhiteSpace([string](Get-Field $b 'nameZh'))) { $badBiome += (Get-Field $b 'value') }
    # 契约 Q17：不带条目计数。多出来的任何字段都算接口漂移 —— 元素形状只有 value / nameZh 两个
    foreach ($p in $b.PSObject.Properties) {
        if ($p.Name -notin @('value', 'nameZh')) { $biomeExtra += ((Get-Field $b 'value') + '.' + $p.Name) }
    }
}
Assert-True ($badBiome.Count -eq 0) '每个生态的 value / nameZh 都非空' `
    ("有 " + $badBiome.Count + " 个生态缺字段：" + (($badBiome | Select-Object -First 5) -join ', '))
Assert-True ($biomeExtra.Count -eq 0) '每个生态只有 value / nameZh（契约 Q17：不带条目计数）' `
    ("多带字段的有 " + $biomeExtra.Count + " 处：" + (($biomeExtra | Select-Object -First 5) -join ', '))

# ─────────────────────────────────────────────────────────────────────────────
# 汇总
# ─────────────────────────────────────────────────────────────────────────────

Section '汇总'

if ($script:Notes.Count -gt 0) {
    Write-Host ("INFO " + $script:Notes.Count + " 条：") -ForegroundColor DarkGray
    foreach ($n in $script:Notes) { Write-Host ("  - " + $n) -ForegroundColor DarkGray }
    Write-Host ''
}

$total = $script:Passed + $script:Failed
if ($script:Failed -eq 0) {
    Write-Host ("全部通过：" + $script:Passed + "/" + $total + " 条断言 PASS") -ForegroundColor Green
    Write-Host ("结束时间：" + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
    exit 0
} else {
    Write-Host ("有失败：" + $script:Passed + "/" + $total + " 条 PASS，" + $script:Failed + " 条 FAIL") -ForegroundColor Red
    Write-Host ("结束时间：" + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
    exit 1
}
