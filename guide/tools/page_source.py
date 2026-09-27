"""页面源文管道（票 #23）：熟食覆写值 + 描述 + 成就。

Cargo 表里查不到、只能读页面散文的那半边数据。解析改用现成的 wikitext 解析库
``mwparserfromhell``，不再手写花括号配对 / 顶层 ``|`` 切分 / 参数表构建。

三样产出（全部基于离线语料，全程不联网）：

* **熟食覆写值** —— ``HungerCooked`` / ``BonusCooked`` / ``HasCookingBonus``。
  数值解析不出来时抛 ``ValueError``，由上层让这一条进失败明细，不静默退回公式。
* **描述** —— 正文散文 + ``List-notes`` + ``Cooking-notes``：剥标记、还原 ``{{PAGENAME}}``、
  按章节标题截断以排除附属章节。取不到就返回 ``None``（条目照常入库，绝不因散文挡条目）。
* **成就** —— 按**模板名**扫 ``{{Badge|X}}``；不按「含 Badge 字样」猜
  （已移除条目页首的 Ambox 里也有 "Badge" 字样，猜法会凭空造出成就）。

与当初 Java 版的取舍差别（普查数字见 docs/crawler-容错普查.md）：
``iteminfobox`` 变体白名单、多 Infobox 认领、括号不配对、``\\r\\n`` 归一
四项在 134 页语料里命中为 0，重写时删掉，并用金标准比对证明不影响结果。
"""

import re
from dataclasses import dataclass

import mwparserfromhell

# 模板名归一：小写 + 只留字母数字，于是 "Infobox item" / "infobox_item" 是同一个名字
INFOBOX_TEMPLATE = "infoboxitem"
BADGE_TEMPLATE = "badge"
PAGENAME_TEMPLATE = "pagename"

# 渲染成「第一个位置参数」的排版类模板（模板名本身不是句子的一部分）
DISPLAY_ONLY_TEMPLATES = {
    "peakgame", "item", "injury", "hunger", "poison", "spores", "bonus",
    "heat", "cold", "curse", "drowsy", "thorns", "petrify", "moraleboost",
}

# 无参数、且渲染成固定文字的模板（取值来自模板源码，不是猜的）
FIXED_TEXT_TEMPLATES = {"peakgame": "PEAK"}

# 熟食侧参数名（归一后）
HUNGER_COOKED = "hungercooked"
BONUS_COOKED = "bonuscooked"
HAS_COOKING_BONUS = "hascookingbonus"
COOKING_NOTES = "cookingnotes"
LIST_NOTES = "listnotes"

COOKING_PREFIX = "烹饪："

# 整块丢弃：表格与 HTML 注释、<gallery>/<ref> 扩展标签
DROP_BLOCK = re.compile(r"(?s)\{\|.*?\|}|<!--.*?-->")
COMMENT = re.compile(r"<!--.*?-->", re.DOTALL)
DROP_ELEMENT = re.compile(r"<(gallery|ref)\b[^>]*>.*?</\1\s*>", re.DOTALL | re.IGNORECASE)

# 行首的列表 / 缩进标记（* # ; :）——列表项切行的位置标记，不是内容
LINE_MARKERS = re.compile(r"(?m)^\s*[*#;:]+\s*")

# [[File:…]] / [[Image:…]] 图片链接：从 [[File: 删到**第一个** ]]（与旧版非贪婪正则同口径）。
# 说明文字里的内层链接会让删除提前收尾、残留一点文字，这是既有行为，照搬以保住金标准。
MEDIA_LINK = re.compile(r"\[\[\s*(?:File|Image)\s*:.*?]]", re.DOTALL | re.IGNORECASE)

# 正文里的章节标题（== X == / === X ===），从这里往后都是 Trivia / Gallery / Patch history 一类附属内容
SECTION_HEADING = re.compile(r"^(={2,})\s*(.+?)\s*\1$")

# 段落之间的空行。数据源实测换行全是 \n（\r\n 归一那项防御命中为 0，已删）
BLANK_LINE = re.compile(r"(?:\n\s*){2,}")

# 带说明的站外链 [https://… 文字] → 文字；不带说明的 → 空
EXTERNAL_LINK = re.compile(r"\[\s*(?:https?|ftp)://\S*\s*([^\]]*?)\s*]")

# 行首的列表 / 缩进标记（mwparserfromhell 会把它们解析成 Tag 节点）
LIST_MARKUP = {"*", "#", ";", ":"}

# 粗体 / 斜体（先 3 撇再 2 撇，单独 1 撇是普通标点如 Scout's，不动）
BOLD = re.compile(r"'''")
ITALIC = re.compile(r"''")

# 剥完链接后可能残留的方括号 / 花括号
RESIDUAL_MARKUP = re.compile(r"[\[\]{}]")

# 被整块删除的内容之前悬空的冠词/介词（"grants the player the {{Badge|…}}." → "grants the player the."）
DANGLING_ARTICLE = re.compile(r"\s+\b(?:the|a|an|of|in|to)\b\s*(?=[.,;]|$)", re.IGNORECASE)

# 整块内容被删掉后留下的空位：行内连续空格、句末孤零零的标点、行首标点
EMPTY_SLOT = re.compile(r"\s{2,}|\s+([.,;:])|^\s*([.,;:])\s*")


def normalize_key(raw: str) -> str:
    """模板名 / 参数名的归一写法：小写 + 只留字母数字。"""
    return "".join(c for c in raw if c.isalnum()).lower()


@dataclass
class CookParams:
    """页面源文里查不到的熟食参数。全部没写时就是 EMPTY。"""

    hunger_cooked: float | None = None
    bonus_cooked: float | None = None
    has_cooking_bonus: str | None = None

    def suppresses_cooking_bonus(self) -> bool:
        """HasCookingBonus = no/breaks：烹饪不给饱食/加成（表现为熟食值不生成）。"""
        return self.has_cooking_bonus in ("no", "breaks")


EMPTY_PARAMS = CookParams()


# --------------------------------------------------------------------------------------
# 结构化提取（mwparserfromhell）
# --------------------------------------------------------------------------------------

def _infoboxes(wikitext: str) -> list[dict]:
    """页面上所有 `{{Infobox item}}` 的参数表，按出现顺序。

    先剥 HTML 注释再解析：注释可能落在取值里（如 `| Hunger = -30<!-- 测试用 -->`），
    不剥的话解析出的数字会带上注释文本、被当成解析失败。

    只认一个模板名（普查里 `iteminfobox` 变体命中为 0，已删）。多 Infobox 页面在 134 页里
    也是 0，所以不再做 display 认领 —— 取第一个框即可。
    """
    code = mwparserfromhell.parse(COMMENT.sub("", wikitext))
    return [_params(t) for t in code.filter_templates(recursive=False)
            if normalize_key(str(t.name)) == INFOBOX_TEMPLATE]


def _params(template) -> dict:
    """模板 → 参数表：key 归一，重名取第一个（与旧版 putIfAbsent 同口径）。"""
    params: dict[str, str] = {}
    for param in template.params:
        # 位置参数（没有 key=value）不是命名参数，跳过
        if not param.showkey:
            continue
        key = normalize_key(str(param.name))
        if key and key not in params:
            params[key] = str(param.value).strip()
    return params


def _blank_to_null(raw: str | None) -> str | None:
    if raw is None:
        return None
    return raw.strip() or None


def read_params(wikitext: str | None, display_name: str | None) -> CookParams:
    """页面源文 → 熟食覆写参数。

    @throws ValueError 覆写值写了但解析不出数字时抛出，由上层记进失败明细，
            绝不静默退回公式（那会得到一个"看起来对但没人发现错了"的结果）
    """
    if not wikitext or not display_name or not display_name.strip():
        return EMPTY_PARAMS
    infoboxes = _infoboxes(wikitext)
    if not infoboxes:
        return EMPTY_PARAMS
    infobox = infoboxes[0]
    return CookParams(
        hunger_cooked=_number(infobox, HUNGER_COOKED),
        bonus_cooked=_number(infobox, BONUS_COOKED),
        has_cooking_bonus=_lower_or_null(infobox.get(HAS_COOKING_BONUS)),
    )


def _number(infobox: dict, key: str) -> float | None:
    raw = _blank_to_null(infobox.get(key))
    if raw is None:
        return None
    try:
        return float(raw)
    except ValueError as exc:
        raise ValueError(f"页面源文里的 {key} 不是数字:{raw}") from exc


def _lower_or_null(raw: str | None) -> str | None:
    value = _blank_to_null(raw)
    return None if value is None else value.lower()


def read_page_text(wikitext: str | None, page_name: str | None) -> tuple[str | None, str | None]:
    """页面源文 → (description, achievement)。

    这是展示用自由文本，**不抛异常**：取不到就留空，不该因为它把条目挡在库外 ——
    与熟食侧"数值解析不出就抛"刻意相反。

    @return (描述, 逗号拼接的成就名)；都没有时为 (None, None)
    """
    if not wikitext or not wikitext.strip():
        return None, None
    text = COMMENT.sub("", wikitext)
    infoboxes = _infoboxes(text)
    infobox = infoboxes[0] if infoboxes else {}
    return _build_description(text, infobox, page_name), _badges(text)


def _build_description(text: str, infobox: dict, page_name: str | None) -> str | None:
    """description = 正文散文 + listNotes + cookingNotes（任意一段为空就跳过那一段）。"""
    parts: list[str] = []
    prose = _prose(text, page_name)
    if prose:
        parts.append(prose)
    list_notes = _wikitext_to_plain(infobox.get(LIST_NOTES), page_name)
    if list_notes:
        parts.append(list_notes)
    cooking_notes = _wikitext_to_plain(infobox.get(COOKING_NOTES), page_name)
    if cooking_notes:
        parts.append(COOKING_PREFIX + cooking_notes)
    return " ".join(parts) if parts else None


def _prose(text: str, page_name: str | None) -> str | None:
    """正文：跳过前置模板，取到第一个章节标题为止。

    保留「首段起至首个章节标题」的**全部**段落，而不是只取第一段 ——
    徽章引用常在第 4 段（Hot Dog），附属章节（Trivia / Gallery / Patch history）才该排除。
    """
    paragraphs: list[str] = []
    for paragraph in _paragraphs(_skip_leading_templates(text)):
        if _has_section_heading(paragraph):
            break
        plain = LINE_MARKERS.sub("", strip_markup(paragraph, page_name)).strip()
        folded = re.sub(r"\s+", " ", plain).strip()
        if not folded:
            continue
        paragraphs.append(folded)
    return " ".join(paragraphs) if paragraphs else None


def _skip_leading_templates(text: str) -> str:
    """跳过正文之前的所有顶层模板与它们之间的空白。

    前置模板不止一个、顺序也不固定（``{{stub}}`` / ``{{For}}`` / ``{{Infobox item}}``…），
    逐个往后跳才能把这一堆写法一次收干净。跳完后若是文本，那就是正文起点。
    """
    nodes = list(mwparserfromhell.parse(text).nodes)
    start = 0
    while start < len(nodes):
        node = nodes[start]
        if isinstance(node, mwparserfromhell.nodes.Template):
            start += 1
        elif isinstance(node, mwparserfromhell.nodes.Text) and not str(node).strip():
            start += 1
        else:
            break
    return "".join(str(node) for node in nodes[start:])


def _paragraphs(body: str) -> list[str]:
    return [piece.strip() for piece in BLANK_LINE.split(body) if piece.strip()]


def _has_section_heading(paragraph: str) -> bool:
    return any(SECTION_HEADING.match(line.strip()) for line in paragraph.split("\n"))


def _wikitext_to_plain(raw: str | None, page_name: str | None) -> str | None:
    """Infobox 里的说明类参数（listNotes / cookingNotes）→ 纯文本。

    实测写成 ``* '''Source''': …`` 的列表形态，剥完标记要把行首项目符号收掉。
    """
    stripped = LINE_MARKERS.sub("", strip_markup(raw, page_name))
    if not stripped:
        return None
    lines = [line.strip() for line in stripped.split("\n")]
    lines = [line for line in lines if line]
    return " ".join(lines) if lines else None


def _badges(text: str) -> str | None:
    """全文收集徽章引用名（``{{Badge|X}}`` / ``{{badge|X}}``），按出现顺序去重，逗号拼接。

    扫描范围是整个页面：徽章句子没有固定位置。只认模板名是 badge 的宏 ——
    已移除条目页首 Ambox 里的 ``icon = Speed Climber Badge.png`` 天然不会误命中。
    """
    badges: list[str] = []
    for template in mwparserfromhell.parse(text).filter_templates(recursive=False):
        if normalize_key(str(template.name)) != BADGE_TEMPLATE:
            continue
        for param in template.params:
            name = _wikitext_to_plain(str(param.value), None)
            if name and name not in badges:
                badges.append(name)
    return ", ".join(badges) if badges else None


# --------------------------------------------------------------------------------------
# 文本清洗（把一段 wikitext 剥成纯文本）
# --------------------------------------------------------------------------------------

def strip_markup(raw: str | None, page_name: str | None) -> str:
    """剥掉 wikitext 标记；`{{PAGENAME}}` 还原成条目名（为 None 时剥成空串）。"""
    if raw is None:
        return ""
    text = DROP_BLOCK.sub("", raw)
    text = DROP_ELEMENT.sub("", text)
    text = MEDIA_LINK.sub("", text)
    text = _render(mwparserfromhell.parse(text), page_name)
    text = DANGLING_ARTICLE.sub(" ", text)
    text = BOLD.sub("", text)
    text = ITALIC.sub("", text)
    text = RESIDUAL_MARKUP.sub("", text)
    # 只取第 1 组：第 3 个分支（行首标点）因此替换为空串，与旧版 replaceAll("$1") 同口径
    text = EMPTY_SLOT.sub(lambda m: m.group(1) or "", text)
    text = DANGLING_ARTICLE.sub(" ", text)
    return text.strip()


def _render(code, page_name: str | None) -> str:
    return "".join(_render_node(node, page_name) for node in code.nodes)


def _render_node(node, page_name: str | None) -> str:
    if isinstance(node, mwparserfromhell.nodes.Template):
        return _render_template(node, page_name)
    if isinstance(node, mwparserfromhell.nodes.Wikilink):
        # [[目标]] → 目标；[[目标|显示文本]] → 显示文本
        return _render(node.text if node.text is not None else node.title, page_name)
    if isinstance(node, mwparserfromhell.nodes.ExternalLink):
        return EXTERNAL_LINK.sub(r"\1", str(node))
    if isinstance(node, mwparserfromhell.nodes.Tag):
        # 行首的列表/缩进标记（* # ; :）会被解析成隐式 <li> 之类的标签，按字面保留，
        # 交给下游按行收掉 —— 丢掉它会让换行与下一个列表项之间的空格一起消失
        if str(node) in LIST_MARKUP:
            return str(node)
        # 其余只删标签本身、保留其中的内容（<br/> 无内容、'''粗体''' / ''斜体'' 的记号也在这里被收掉）
        return _render(node.contents, page_name) if node.contents is not None else ""
    if isinstance(node, mwparserfromhell.nodes.Comment):
        return ""
    return str(node)


def _render_template(template, page_name: str | None) -> str:
    key = normalize_key(str(template.name))
    if key == PAGENAME_TEMPLATE:
        return page_name or ""
    if key in DISPLAY_ONLY_TEMPLATES:
        # 有参数 → 取第一个参数（{{Item|Marshmallow}} → Marshmallow）；无参数 → 固定文字或删空
        if template.params:
            return _render(template.params[0].value, page_name).strip()
        return FIXED_TEXT_TEMPLATES.get(key, "")
    if key == BADGE_TEMPLATE:
        # 名字已进 achievement 列，正文里留残缺模板名只会读成半截话
        return ""
    # 其余模板只留模板名，参数一律丢弃
    return str(template.name).strip()
