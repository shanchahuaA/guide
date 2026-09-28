"""图标本地化（票 #24）：条目英文名 → 数据源直链 → 本地图标目录。

图鉴条目的 ``icon`` 列存的是**相对路径**（``/icons/Hot_Dog.png``），文件落在后端
对外暴露的那个目录（``application.yml`` 的 ``guide.icon-dir``；从 ``guide/``
启动后端就是 ``guide/icons/``）。两个都不动：应用与小程序读到的 URL 与之前一模一样。

三件事按这个顺序做：

1. **文件名规则** —— 与应用侧 ``utils/IconFileNames``（算 slug 用的那份）同一套：空格换下划线，
   再按白名单 ``[A-Za-z0-9._'()-]`` 把其余字符换成下划线。这条白名单是两端约束的交集：
   URL 路径段里不用转义（所以 ``icon`` 列的路径不必再编码），又都是合法 Windows 文件名。
   数据源上真有一个 ``File:Bugle?.png`` —— ``?`` 既建不出 Windows 文件，又会被 URL 当成
   query 的起点，靠百分号编码也救不回来，所以本地名统一改写（改写时记一条警告）。
2. **直链走 api.php 的 imageinfo** —— 不走 ``Special:FilePath``，那条路被 Cloudflare 403
   挡着。标题按 ``File:<英文名>.png`` 推，50 个一包；一包失败只影响那一包里的图标。
3. **下载** —— 一张一次，沿用采集那条通道的 UA（图片直链可能落在另一个主机上，
   但挡在前面的是同一家 Cloudflare）；先落临时文件再改名，中途失败不会在目录里留下半张 PNG。

失败粒度是**单个文件**：某张下不动只记进报告、对应条目图标留空，既不中断整批，
也不回滚旁边已经下好的。只有图标目录建不出来才算整体失败 —— 那确实一张也存不下，
装成 134 条单文件失败只会让报告更难读。

这里是唯一一处联网取二进制的地方；与 ``crawl_items`` / ``freeze_baseline`` 重复的几处小工具
（``http_json``、失败原因取异常文本、UA 与 api.php 常量）是有意留着的，合并是独立的一票。
"""

import json
import re
import tempfile
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

WIKI_API = "https://peak.wiki.gg/api.php"
# 伪装 Googlebot 是绕过 Cloudflare 的实测通道（重写时照搬的原口径，实测过的）
USER_AGENT = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"

# 与 application.yml 的 guide.icon-url-prefix 一致（WebMvcConfig 的静态映射也用这个）
DEFAULT_URL_PREFIX = "/icons"

# 图标落盘目录的默认值，就是后端对外暴露的那一个：application.yml 的 guide.icon-dir 是 `icons`，
# 而后端的工作目录必须是 guide/（见 CLAUDE.md），两者指向同一个目录。
# 与 URL 前缀放在一起，是因为"写盘方"与"暴露方"必须对齐 —— 各写一份默认值迟早会对不上。
DEFAULT_DIRECTORY = Path(__file__).resolve().parents[2] / "guide" / "icons"

# 白名单之外的字符一律换成下划线（与 utils/IconFileNames 同一套白名单 —— 应用侧算 slug 用的也是它）
SAFE = re.compile(r"[^A-Za-z0-9._'()-]")

# 数据源的图片命名空间，以及那 134 张图统一的扩展名
FILE_NAMESPACE = "File:"
SUFFIX = ".png"

# MediaWiki 对普通用户的上限：一次最多查 50 个标题
TITLES_PER_REQUEST = 50


def wiki_title(display: str) -> str:
    """数据源上的文件标题，用于 imageinfo 查询。

    空格保持原样即可：MediaWiki 把标题里的空格和下划线视为同一个字符。
    """
    return FILE_NAMESPACE + display + SUFFIX


def file_name(display: str) -> str:
    """本地文件名，同时也是 ``icon`` 列里 URL 路径的最后一段。"""
    return SAFE.sub("_", display.replace(" ", "_")) + SUFFIX


def needs_rewrite(display: str) -> bool:
    """文件名里含有被换掉的字符时为 true（落盘时据此记一条警告，让实跑里的意外看得见）。"""
    return file_name(display) != display.replace(" ", "_") + SUFFIX


def icon_path(display: str, url_prefix: str = DEFAULT_URL_PREFIX) -> str:
    """条目 ``icon`` 列的取值，如 ``/icons/Hot_Dog.png``。"""
    return url_prefix + "/" + file_name(display)


@dataclass
class Result:
    """一次图标本地化的结果。

    :param path_by_display: 英文名 → icon 列的相对路径；**只有下成功的那些**在里面，
                            不在里面的条目按"图标留空"落库
    :param failures: 失败明细，原样进采集报告
    :param warnings: 文件名被改写一类的提示
    """

    path_by_display: dict[str, str] = field(default_factory=dict)
    failures: list[dict] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)


# --------------------------------------------------------------------------------------
# 数据源访问：查直链、取二进制（都只走 api.php 那条通道）
# --------------------------------------------------------------------------------------

def http_json(query: str) -> dict:
    request = urllib.request.Request(WIKI_API + "?" + query, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.loads(response.read().decode("utf-8"))


def fetch_bytes(url: str) -> bytes:
    """取一张图；下载走的是与 api.php 同一道 Cloudflare，必须沿用同一个 UA。"""
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def fetch_direct_urls(displays: list[str], http_json=http_json) -> tuple[dict, list]:
    """分批查文件直链。

    一包的 HTTP 失败只影响那一包里的文件（逐个记进报告），其余包照常查 ——
    数据源抖一下不该让整批图标全空。

    :return (英文名 → 直链, 失败明细)；数据源上不存在的文件不进结果，同样记进失败明细
    """
    urls: dict[str, str] = {}
    failures: list[dict] = []

    for start in range(0, len(displays), TITLES_PER_REQUEST):
        batch = displays[start:start + TITLES_PER_REQUEST]
        titles = [wiki_title(display) for display in batch]
        try:
            url_by_title = _retry_once(lambda: _query_imageinfo(titles, http_json))
        except Exception as exc:
            for display in batch:
                failures.append(_failure(display, "查询直链失败:" + _reason(exc)))
            continue

        for display in batch:
            url = url_by_title.get(wiki_title(display))
            if url is None:
                failures.append(_failure(display, "数据源上没有这个文件:" + wiki_title(display)))
            else:
                urls[display] = url
    return urls, failures


def _query_imageinfo(titles: list[str], http_json) -> dict:
    """一个包的文件直链查询：{文件标题: 直链}。

    用 formatversion=2 让 pages 变成数组、每项自带 title；v1 的形状是以 pageid 为键的对象，
    多个不存在的标题会挤在同一个 "-1" 键上互相覆盖。
    """
    query = ("action=query&format=json&formatversion=2&prop=imageinfo&iiprop=url&titles="
             + urllib.parse.quote("|".join(titles), safe=""))
    response = http_json(query)

    error = response.get("error")
    if error:
        raise RuntimeError("数据源返回错误:" + json.dumps(error, ensure_ascii=False))
    query_obj = response.get("query") or {}
    pages = query_obj.get("pages")
    if pages is None:
        raise RuntimeError("数据源响应里没有 query.pages 字段")

    by_title: dict[str, str] = {}
    for page in pages:
        # 文件不存在的行没有 imageinfo：那类标题不进结果，由调用方按"没有这个文件"记报告
        info = page.get("imageinfo") or []
        url = (info[0] or {}).get("url") if info else None
        if page.get("title") and url:
            by_title[page["title"]] = url

    # 数据源会对标题做规范化（下划线↔空格、首字母大写），响应里的 title 因此可能跟请求的写法不同：
    # 先按 normalized 对照还原成**请求时**的标题，对不上再拿请求时的写法直接找一次 ——
    # 两条都试过才判定"数据源上没有这个文件"
    normalized = {entry["from"]: entry["to"] for entry in query_obj.get("normalized") or []}
    urls: dict[str, str] = {}
    for title in titles:
        url = by_title.get(normalized.get(title, title)) or by_title.get(title)
        if url is not None:
            urls[title] = url
    return urls


# --------------------------------------------------------------------------------------
# 下载与落盘
# --------------------------------------------------------------------------------------

def download_all(displays: list[str], directory: Path, *,
                 url_prefix: str = DEFAULT_URL_PREFIX,
                 resolve_urls=fetch_direct_urls,
                 fetch_bytes=fetch_bytes) -> Result:
    """把这一批条目的图标全部落到 ``directory`` 目录。

    :param displays: 条目英文名，去重由调用方负责；文件名由它推出
    :param directory: 后端对外暴露的图标目录
    :raises OSError: 图标目录建不出来时抛出，由调用方记进报告
    """
    result = Result()
    if not displays:
        return result

    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)

    # 直链是批量的（50 个标题一包），下载是一张一次的，所以先把直链整批查完再逐张下
    url_by_display, failures = resolve_urls(displays)
    result.failures.extend(failures)

    for display in displays:
        url = url_by_display.get(display)
        if url is None:
            continue    # 查直链那一步已经把这一个记进失败了
        try:
            _store(_download(url, fetch_bytes), directory, display, result.warnings)
        except Exception as exc:
            result.failures.append(_failure(display, "下载失败:" + _reason(exc)))
        else:
            result.path_by_display[display] = icon_path(display, url_prefix)

    return result


def _download(url: str, fetch_bytes) -> bytes:
    """下一张；失败由调用方记报告，这里只负责把 HTTP 响应变成非空字节。

    200 也可能是空体（被拦下时对方偶尔就这么回），不能当成下好了往磁盘上写。
    """
    body = _retry_once(lambda: fetch_bytes(url))
    if not body:
        raise ValueError("响应体为空")
    return body


def _store(body: bytes, directory: Path, display: str, warnings: list[str]) -> None:
    """写盘：先落临时文件再改名，中途失败不会在目录里留下半张 PNG（那会被当成成品发出去）。"""
    name = file_name(display)
    if needs_rewrite(display):
        warnings.append(f"图标文件名含 URL / Windows 文件名不安全字符，已改写:{display} → {name}")

    handle, temp = tempfile.mkstemp(dir=directory, prefix="icon-", suffix=".part")
    try:
        with open(handle, "wb") as file:
            file.write(body)
        Path(temp).replace(directory / name)
    finally:
        Path(temp).unlink(missing_ok=True)


def _retry_once(call):
    """重试一次。

    数据源在境外，单次失败多是抖动而不是真没有；采集本身可以重复触发，
    所以兜底只做一层重试，失败的那些照样进报告。
    """
    try:
        return call()
    except Exception:
        return call()


def _failure(display: str, reason: str) -> dict:
    return {"nameEn": display, "reason": reason}


def _reason(exc: Exception) -> str:
    """异常没有 message 时退回类名，报告里不留空白。"""
    message = str(exc).strip()
    return message or type(exc).__name__
