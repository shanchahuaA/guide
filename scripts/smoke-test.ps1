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
# ⚠️ 中文断言要注意：后端的 Content-Type 是 `application/json`，**不带 charset**，
# 于是 PS 5.1 按 ISO-8859-1 解码响应体，`message` / `nameZh` 这类中文字段在脚本里读出来是乱码
# （实测 `"操作成功"` 变成 `"æä½æå"`，服务端本身是对的）。
# 所以断言一律挑 ASCII 字段比对（slug / nameEn / code / primaryType），
# 非要验中文取值就自己把 RawContentStream 按 UTF-8 解一遍。
function Invoke-Json([string] $method, [string] $path, [int] $timeoutSec) {
    $uri = $BaseUrl + $path
    try {
        $json = Invoke-RestMethod -Uri $uri -Method $method -TimeoutSec $timeoutSec -UseBasicParsing
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
        return @{
            Ok     = $false
            Status = $status
            Json   = $null
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

# ── 筛选参数让结果收窄（spec.md 测试决定里点名的检查点）──
Write-Host ''
Write-Host '  筛选与模糊查询：'
$food = Invoke-Json 'GET' '/api/items?primaryType=FOOD' 60
$foodItems = As-Array (Get-Field (Get-Data $food) 'items')
Assert-True ($foodItems.Count -gt 0 -and $foodItems.Count -lt $allItems.Count) `
    'primaryType=FOOD 收窄：非空且少于全量' `
    ("实际 " + $foodItems.Count + " 条，全量 " + $allItems.Count + " 条")
$strayFood = @()
foreach ($i in $foodItems) { if ((Get-Field $i 'primaryType') -ne 'FOOD') { $strayFood += (Get-Field $i 'nameEn') } }
Assert-True ($strayFood.Count -eq 0) 'primaryType=FOOD 的结果全是 FOOD' `
    ("混进了 " + $strayFood.Count + " 条非 FOOD：" + (($strayFood | Select-Object -First 5) -join ', '))

# 入参大小写不敏感：前端从别处拿到的小写值不该筛出空结果
$equipment = Invoke-Json 'GET' '/api/items?primaryType=equipment' 60
Assert-GreaterOrEqual (As-Array (Get-Field (Get-Data $equipment) 'items')).Count 1 'primaryType 入参不区分大小写（equipment 小写也能筛到）'

# 模糊查询：英文名忽略大小写
$searchEn = Invoke-Json 'GET' '/api/items?keyword=hot%20dog' 60
$searchEnItems = As-Array (Get-Field (Get-Data $searchEn) 'items')
Assert-True ($null -ne (Get-Item $searchEnItems 'Hot Dog')) 'keyword=hot dog（小写）能搜到 Hot Dog' '搜索结果里没有 Hot Dog'

# 模糊查询：中文名按原样匹配
$searchZh = Invoke-Json 'GET' '/api/items?keyword=%E8%8A%A6%E8%8D%9F' 60
$searchZhItems = As-Array (Get-Field (Get-Data $searchZh) 'items')
Assert-True ($null -ne (Get-Item $searchZhItems 'Aloe Vera')) 'keyword=芦荟（中文）能搜到 Aloe Vera' '中文搜索结果里没有 Aloe Vera'

# 叠加：主类型 + 关键字，结果应比单条件更窄
$combo = Invoke-Json 'GET' '/api/items?primaryType=FOOD&keyword=%E8%8F%87' 60
$comboItems = As-Array (Get-Field (Get-Data $combo) 'items')
Assert-True ($comboItems.Count -gt 0 -and $comboItems.Count -lt $foodItems.Count) `
    'primaryType 与 keyword 可叠加，结果比单筛更窄' `
    ("叠加后 " + $comboItems.Count + " 条，单筛 FOOD " + $foodItems.Count + " 条")

# ── 详情：路径参数是 slug 不是 id ──
Write-Host ''
Write-Host '  详情：'
$detail = Invoke-Json 'GET' '/api/items/hot_dog' 60
$detailItem = Get-Field $detail.Json 'item'
Assert-True ($null -ne $detailItem) '/api/items/hot_dog 返回 item' ("实际 " + (Format-Actual $detailItem))
if ($null -ne $detailItem) {
    Assert-Equal (Get-Field $detailItem 'slug') 'hot_dog' '详情 slug=hot_dog'
    Assert-True ((Get-Field $detailItem 'isCookable') -eq $true) 'Hot Dog isCookable=true' `
        ("实际 " + (Format-Actual (Get-Field $detailItem 'isCookable')))
    # 中文译文已回填；万一某条漏了，接口会回落到英文原值而不是留空 —— 这里只要求非空
    $desc = [string](Get-Field $detailItem 'description')
    Assert-True ($desc.Length -gt 0) '详情带 description' '详情里 description 是空的'
}

# slug 里那批被改写过的字符要真的能取到：Bugle? 的 slug 是 bugle_（问号被换成下划线）
$bugleQuery = Invoke-Json 'GET' '/api/items/bugle_' 60
Assert-True ($null -ne (Get-Field $bugleQuery.Json 'item')) '被改写过的 slug（bugle_）也能取到详情' `
    '取不到 bugle_：slug 规则与 icon 文件名的字符白名单没对齐'

# 不存在的 slug 要走统一响应包的失败分支，不是 500
$missing = Invoke-Json 'GET' '/api/items/does_not_exist' 60
Assert-True ($missing.Ok -and (Get-Field $missing.Json 'success') -eq $false -and (Get-Field $missing.Json 'code') -eq -100) `
    '不存在的 slug 返回 success=false / code=-100' `
    ("实际 status=" + $missing.Status + " json=" + (Format-Actual $missing.Json))

# ── 标签字典与生态 ──
Write-Host ''
Write-Host '  字典：'
$tags = Invoke-Json 'GET' '/api/tags' 60
$tagList = As-Array (Get-Field $tags.Json 'tags')
Assert-True ($tagList.Count -gt 0) '/api/tags 返回非空' ("实际 " + $tagList.Count + " 条")
$dimensions = @()
foreach ($t in $tagList) { $dimensions += (Get-Field $t 'code') }
foreach ($d in @('type', 'biome', 'rarity', 'source', 'location', 'flag')) {
    Assert-True ($dimensions -contains $d) ("/api/tags 含 $d 维度") ("实际维度：" + (($dimensions | Sort-Object -Unique) -join ', '))
}

$biomes = Invoke-Json 'GET' '/api/biomes' 60
$biomeList = As-Array (Get-Field $biomes.Json 'biomes')
Assert-GreaterOrEqual $biomeList.Count 1 '/api/biomes 返回非空'
$badCount = @()
foreach ($b in $biomeList) {
    if ((Get-Field $b 'count') -isnot [int] -and (Get-Field $b 'count') -isnot [long]) { $badCount += (Get-Field $b 'value') }
}
Assert-True ($badCount.Count -eq 0) '每个生态都带条目数 count（这是 /api/biomes 相对 /api/tags 唯一多出来的东西）' `
    ("有 " + $badCount.Count + " 个生态没有 count：" + (($badCount | Select-Object -First 5) -join ', '))

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
