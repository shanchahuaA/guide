package org.example.guide.crawler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据源访问。
 *
 * 只走 MediaWiki 的 {@code api.php},不碰 HTML 页面 —— 后者有 Cloudflare 的 JS 挑战,
 * 而且散文里也没有结构化字段。
 *
 * 用 Spring 自带的 RestClient,零新增依赖。
 */
@Component
public class WikiApiClient {

    private static final Logger log = LoggerFactory.getLogger(WikiApiClient.class);

    /**
     * 伪装 Googlebot 是绕过 Cloudflare 的实测可行通道。
     * 注意:带上这个 UA 之后 {@code api.php} 通,但 wiki 的 HTML 页面仍然 403。
     *
     * 包内可见:图标下载走的是同一个数据源、同一道 Cloudflare,必须沿用同一个 UA(见 ItemIconDownloader)。
     */
    static final String USER_AGENT = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)";

    /**
     * 采集侧所有 HTTP 调用的超时:数据源在境外,连接慢是常态;
     * 但采集是管理员点一次等一次的前台操作,不能在某一个连接上无限等下去。
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    /** 全量只有 134 行,一次请求拉完,不需要分页 */
    private static final int LIMIT = 500;

    /**
     * 批量取页面源文时一个包最多塞多少个标题。
     *
     * 50 是 MediaWiki 对普通用户(非 bot,拿不到 apihighlimits)的上限,134 个页面因此约 3 次请求。
     */
    private static final int WIKITEXT_TITLES_PER_REQUEST = 50;

    /**
     * 一次能查多少个文件标题。50 是 MediaWiki 对普通用户(非 bot)的上限,
     * 134 个图标因此分成 3 包。
     */
    public static final int MAX_TITLES_PER_REQUEST = 50;

    private static final String TABLES = "Items";

    /**
     * 要拉的字段。除了 _pageName 起了别名(数据源里以下划线开头的字段名不便直接当 key),
     * 其余都按原名取,由 CargoItemRow 逐字段收口。
     *
     * 加字段要同时改 CargoItemRow.fromMap —— 那份清单是强类型的,不会跟着这里自动长出来。
     */
    private static final String FIELDS = String.join(",",
            "_pageName=page", "display", "type", "rarity", "biome", "location", "source",
            "uses", "weight", "hunger", "bonus", "heat", "cold", "coldTime", "injury",
            "poison", "poisonTime", "poisonStart", "spores", "drowsy", "curse", "thorns", "removed");

    private final String apiBaseUrl;
    private final RestClient restClient;

    public WikiApiClient(@Value("${guide.crawler.api-base-url:https://peak.wiki.gg/api.php}") String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
        this.restClient = RestClient.builder()
                .baseUrl(apiBaseUrl)
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .requestFactory(requestFactory())
                .build();
    }

    /**
     * 采集侧共用一套超时 —— 分头设置迟早会漏掉一处,漏掉的那处就是"演示日卡住不动"。
     * 图标是二进制下载,也走这一份(见 ItemIconDownloader)。
     */
    static ClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    /**
     * 拉取 Cargo 表 Items 的全量行。
     *
     * @throws RuntimeException HTTP 失败(含被 Cloudflare 拦下)或响应无法解析时抛出,由调用方落进采集报告
     */
    public List<CargoItemRow> fetchAllItems() {
        CargoQueryResponse response = restClient.get()
                .uri(builder -> builder
                        .queryParam("action", "cargoquery")
                        .queryParam("tables", TABLES)
                        .queryParam("fields", FIELDS)
                        .queryParam("limit", LIMIT)
                        .queryParam("format", "json")
                        .build())
                .retrieve()
                .body(CargoQueryResponse.class);

        if (response == null) {
            throw new IllegalStateException("数据源返回了空响应");
        }
        if (response.error() != null && !response.error().isEmpty()) {
            throw new IllegalStateException("数据源返回错误:" + response.error());
        }
        if (response.cargoquery() == null) {
            throw new IllegalStateException("数据源响应里没有 cargoquery 字段");
        }

        List<CargoItemRow> rows = response.cargoquery().stream()
                .map(WikiApiClient::unwrapRow)
                .map(CargoItemRow::fromMap)
                .toList();
        log.info("采集:从数据源拉到 {} 行 Items", rows.size());
        return rows;
    }

    /**
     * 把一行剥到真正的字段表。
     *
     * cargoquery 的每行外面套着一层行包装:{@code {"title": {字段别名: 取值}}},
     * 字段全在 title 里面 —— 不剥这一层,收口出来的每一行都会是空的。
     *
     * 万一数据源哪天改成把字段直接摊平在行上,这里原样返回,不至于把整批采集炸掉。
     */
    private static Map<String, Object> unwrapRow(Map<String, Object> row) {
        Object title = row.get("title");
        if (title instanceof Map<?, ?> fields) {
            Map<String, Object> unwrapped = new HashMap<>();
            fields.forEach((key, value) -> unwrapped.put(String.valueOf(key), value));
            return unwrapped;
        }
        return row;
    }

    /**
     * 批量查文件直链。
     *
     * 图标不走 {@code Special:FilePath} —— 那条路被 Cloudflare 403 挡着;
     * 走 {@code action=query&prop=imageinfo&iiprop=url} 拿直链,再自己去下这张图。
     *
     * <p>这个请求刻意**不用** {@code uri(builder -> ...)} 那套模板式拼 URI,而是自己编码、
     * 拼成 {@link URI} 再交给 {@code uri(URI)}:标题里带 {@code ?}
     * (数据源上真有一个 {@code File:Bugle?.png})和 {@code |} 分隔符,两者都必须编码进 query,
     * 而"模板那套会替我们编几次"只能靠翻源码确认。自己编一次,是代码里看得见的一行。
     *
     * @param fileTitles 数据源上的文件标题(含 {@code File:} 前缀);切包由调用方负责
     *                   (见 {@code MAX_TITLES_PER_REQUEST})
     * @return 标题 → 图片直链;标题在数据源上不存在时该键不出现,由调用方记进采集报告
     * @throws RuntimeException HTTP 失败(含被 Cloudflare 拦下)或响应无法解析时抛出,
     *                          与 {@link #fetchAllItems} 一致,由调用方落进采集报告
     */
    public Map<String, String> fetchImageUrls(List<String> fileTitles) {
        if (fileTitles == null || fileTitles.isEmpty()) {
            return Map.of();
        }

        String query = "action=query"
                + "&format=json"
                // formatversion=2 让 pages 变成数组、每项自带 title,并用真布尔标 missing;
                // v1 的形状是以 pageid 为键的对象,多个不存在的标题会挤在同一个 "-1" 键上互相覆盖
                + "&formatversion=2"
                + "&prop=imageinfo"
                + "&iiprop=url"
                + "&titles=" + UriUtils.encodeQueryParam(String.join("|", fileTitles), StandardCharsets.UTF_8);
        URI uri = URI.create(apiBaseUrl + (apiBaseUrl.contains("?") ? "&" : "?") + query);

        ImageInfoResponse response = restClient.get()
                .uri(uri)
                .retrieve()
                .body(ImageInfoResponse.class);

        if (response == null) {
            throw new IllegalStateException("数据源返回了空响应");
        }
        if (response.error() != null && !response.error().isEmpty()) {
            throw new IllegalStateException("数据源返回错误:" + response.error());
        }
        if (response.query() == null || response.query().pages() == null) {
            throw new IllegalStateException("数据源响应里没有 query.pages 字段");
        }

        Map<String, String> urlByTitle = new HashMap<>();
        for (ImageInfoResponse.Page page : response.query().pages()) {
            // 文件不存在的行没有 imageinfo;那类标题不进结果,由调用方按"数据源上没有这个文件"记报告
            if (page.imageinfo() == null || page.imageinfo().isEmpty()) {
                continue;
            }
            String url = page.imageinfo().get(0).url();
            if (url != null && !url.isBlank()) {
                urlByTitle.put(page.title(), url);
            }
        }

        // 数据源会对标题做规范化(下划线↔空格、首字母大写),响应里的 title 因此可能跟我们请求的写法不同。
        // 按 normalized 对照把结果还原成**请求时的标题**,调用方就不用关心规范化这回事。
        Map<String, String> normalized = normalizedTitles(response.query().normalized());

        Map<String, String> urls = new LinkedHashMap<>();
        for (String title : fileTitles) {
            String url = urlByTitle.get(normalized.getOrDefault(title, title));
            if (url == null) {
                url = urlByTitle.get(title);
            }
            if (url != null) {
                urls.put(title, url);
            }
        }
        log.info("图标:{} 个标题查到 {} 条直链", fileTitles.size(), urls.size());
        return urls;
    }

    private static Map<String, String> normalizedTitles(List<ImageInfoResponse.Normalized> normalized) {
        if (normalized == null) {
            return Map.of();
        }
        Map<String, String> byFrom = new HashMap<>();
        normalized.forEach(entry -> byFrom.put(entry.from(), entry.to()));
        return byFrom;
    }

    /**
     * 批量拉取页面源文(wikitext)。
     *
     * 熟食覆盖值、烹饪说明、描述、成就都不在 Cargo 表里,只在页面源文里(见 #14 / #15),
     * 所以采集必须再走一趟 {@code prop=revisions}。
     * 用 {@code rvslots=main} 明确只要主槽的正文 —— 每个页面一次修订足够,不遍历历史。
     *
     * <p>用 {@code formatversion=2} 是为了让 pages 变成**数组**且每项自带 title;
     * v1 以 pageid 为键、标题要么在键上要么在 "-1" 上,对不回请求的标题。
     * 数据源仍会对标题做规范化(下划线↔空格、首字母大写),所以最后按 normalized 对照还原成请求时的写法。
     *
     * @param pageNames 页面名(数据源 Cargo 的 _pageName);超过一个包的内部分批,由本方法负责
     * @return 请求时的页面名 → 页面源文;页面不存在(或没有修订)的名不出现,由调用方记进采集报告
     * @throws RuntimeException HTTP 失败(含被 Cloudflare 拦下)或响应无法解析时抛出,与 {@link #fetchAllItems} 一致
     */
    public Map<String, String> fetchPageWikitext(List<String> pageNames) {
        Map<String, String> wikitextByPage = new LinkedHashMap<>();
        if (pageNames == null || pageNames.isEmpty()) {
            return wikitextByPage;
        }
        for (int from = 0; from < pageNames.size(); from += WIKITEXT_TITLES_PER_REQUEST) {
            int to = Math.min(from + WIKITEXT_TITLES_PER_REQUEST, pageNames.size());
            fetchWikitextBatch(pageNames.subList(from, to), wikitextByPage);
        }
        log.info("熟食:{} 个页面里取到 {} 份源文", pageNames.size(), wikitextByPage.size());
        return wikitextByPage;
    }

    private void fetchWikitextBatch(List<String> pageNames, Map<String, String> into) {
        String query = "action=query"
                + "&format=json"
                + "&formatversion=2"
                + "&prop=revisions"
                + "&rvprop=content"
                + "&rvslots=main"
                + "&titles=" + UriUtils.encodeQueryParam(String.join("|", pageNames), StandardCharsets.UTF_8);
        URI uri = URI.create(apiBaseUrl + (apiBaseUrl.contains("?") ? "&" : "?") + query);

        RevisionQueryResponse response = restClient.get()
                .uri(uri)
                .retrieve()
                .body(RevisionQueryResponse.class);

        if (response == null) {
            throw new IllegalStateException("数据源返回了空响应");
        }
        if (response.error() != null && !response.error().isEmpty()) {
            throw new IllegalStateException("数据源返回错误:" + response.error());
        }
        if (response.query() == null || response.query().pages() == null) {
            throw new IllegalStateException("数据源响应里没有 query.pages 字段");
        }

        // 响应里的 title 是数据源规范化之后的写法,按 normalized 对照还原成我们请求的那个名字
        Map<String, String> normalized = normalizedTitles(response.query().normalized());

        Map<String, String> wikitextByTitle = new HashMap<>();
        for (RevisionQueryResponse.Page page : response.query().pages()) {
            String wikitext = page.mainWikitext();
            if (page.title() != null && wikitext != null) {
                wikitextByTitle.put(page.title(), wikitext);
            }
        }

        for (String pageName : pageNames) {
            String wikitext = wikitextByTitle.get(normalized.getOrDefault(pageName, pageName));
            if (wikitext == null) {
                wikitext = wikitextByTitle.get(pageName);
            }
            if (wikitext != null) {
                into.put(pageName, wikitext);
            }
        }
    }

    /** cargoquery 的响应外壳:每行形如 {"title": {字段别名: 取值}} */
    record CargoQueryResponse(List<Map<String, Object>> cargoquery, Map<String, Object> error) {
    }

    /**
     * revisions 的响应外壳(靠 {@code formatversion=2},pages 是数组且每项自带 title)。
     *
     * 页面不存在时这一项没有 revisions,所以"取不到源文"自然表现为 mainWikitext() 返回 null。
     */
    record RevisionQueryResponse(Query query, Map<String, Object> error) {

        record Query(List<Page> pages, List<ImageInfoResponse.Normalized> normalized) {
        }

        record Page(String title, List<Revision> revisions) {

            /** @return 页面源文;页面不存在或响应里没有正文时返回 null */
            String mainWikitext() {
                if (revisions == null || revisions.isEmpty()) {
                    return null;
                }
                Revision revision = revisions.get(0);
                // 正文落在主槽里,槽内取值的 key 就叫 "*"(MediaWiki 的约定,不是笔误)
                return revision.slots() == null || revision.slots().main() == null
                        ? null : revision.slots().main().get("*");
            }
        }

        record Revision(Slots slots) {
        }

        record Slots(Map<String, String> main) {
        }
    }

    /**
     * imageinfo 的响应外壳。
     *
     * pages 是**数组**且每项自带 title(靠 formatversion=2),所以不依赖响应顺序也能把直链对回标题。
     */
    record ImageInfoResponse(Query query, Map<String, Object> error) {

        record Query(List<Page> pages, List<Normalized> normalized) {
        }

        record Page(String title, List<ImageInfo> imageinfo) {
        }

        record ImageInfo(String url) {
        }

        /** 标题规范化对照:from 是我们请求时的写法,to 是数据源实际认的写法 */
        record Normalized(String from, String to) {
        }
    }
}
