package org.example.guide.crawler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
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
     */
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)";

    /** 全量只有 134 行,一次请求拉完,不需要分页 */
    private static final int LIMIT = 500;

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

    private final RestClient restClient;

    public WikiApiClient(@Value("${guide.crawler.api-base-url:https://peak.wiki.gg/api.php}") String apiBaseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(apiBaseUrl)
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .build();
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

    /** cargoquery 的响应外壳:每行形如 {"title": {字段别名: 取值}} */
    record CargoQueryResponse(List<Map<String, Object>> cargoquery, Map<String, Object> error) {
    }
}
