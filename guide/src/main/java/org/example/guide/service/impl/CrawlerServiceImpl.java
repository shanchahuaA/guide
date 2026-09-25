package org.example.guide.service.impl;

import org.example.guide.crawler.CargoItemRow;
import org.example.guide.crawler.ConvertedItem;
import org.example.guide.crawler.ItemConverter;
import org.example.guide.crawler.WikiApiClient;
import org.example.guide.pojo.dto.CrawlReport;
import org.example.guide.service.ICrawlerService;
import org.example.guide.service.IItemService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 采集编排:拉取 → 逐条转换 → 逐条 upsert → 汇总报告 → 缓存失效。
 */
@Service
public class CrawlerServiceImpl implements ICrawlerService {

    private static final Logger log = LoggerFactory.getLogger(CrawlerServiceImpl.class);

    private final WikiApiClient wikiApiClient;
    private final ItemConverter itemConverter;
    private final IItemService itemService;

    public CrawlerServiceImpl(WikiApiClient wikiApiClient,
                              ItemConverter itemConverter,
                              IItemService itemService) {
        this.wikiApiClient = wikiApiClient;
        this.itemConverter = itemConverter;
        this.itemService = itemService;
    }

    /**
     * 刻意**不加事务**:本方法是"逐条独立提交"的语义。
     * 一旦套上事务,被 catch 掉的那条异常会把事务标记成 rollback-only,
     * 结果是单条失败反倒把整批都回滚掉 —— 与"单条失败跳过"的要求正好相反。
     */
    @Override
    public CrawlReport crawlAll() {
        CrawlReport report = new CrawlReport();

        List<CargoItemRow> rows;
        try {
            rows = wikiApiClient.fetchAllItems();
        } catch (Exception e) {
            // 拉取阶段的 HTTP / 解析失败也落进报告,不冒成 500:
            // 数据源被拦时报告依然可读(拉取行数为 0 + 失败原因),不用靠猜
            log.error("采集拉取阶段失败", e);
            report.setFetchError(failureReason(e));
            return report;
        }

        report.setFetchedRows(rows.size());
        if (rows.isEmpty()) {
            report.addWarning("数据源返回 0 行,请确认接口与字段清单是否仍然有效");
        }

        Map<String, Integer> unknownDictionaryValues = new LinkedHashMap<>();
        for (CargoItemRow row : rows) {
            importRow(row, report, unknownDictionaryValues);
        }
        unknownDictionaryValues.forEach((value, count) -> report.addWarning(
                "字典未知取值 " + value + "(出现 " + count + " 条),已保留原值、中文名留空"));

        evictItemCache();
        return report;
    }

    /**
     * 落库一行:转换 → upsert。
     * 失败只跳过这一条并记进报告,库中已有的条目不动(只 upsert 不反删)。
     */
    private void importRow(CargoItemRow row, CrawlReport report, Map<String, Integer> unknownDictionaryValues) {
        String page = row.getPage();
        String nameEn = row.getDisplay();
        try {
            ConvertedItem converted = itemConverter.convert(row);
            converted.unknownDictionaryValues()
                    .forEach(value -> unknownDictionaryValues.merge(value, 1, Integer::sum));

            if (itemService.batchImportItems(List.of(converted.item()))) {
                report.setSuccessCount(report.getSuccessCount() + 1);
            } else {
                report.addFailure(page, nameEn, "落库返回失败");
            }
        } catch (Exception e) {
            log.warn("采集单条失败,已跳过:page={} display={}", page, nameEn, e);
            report.addFailure(page, nameEn, failureReason(e));
        }
    }

    /**
     * 缓存失效占位:缓存尚未接入(见 issue #4),接入后在这里删掉图鉴的全量缓存。
     * 现在至少留一条日志痕迹,证明采集确实走到了这一步。
     */
    private void evictItemCache() {
        // TODO issue #4 接入缓存后:删除 guide:item: 系列 key
        log.info("缓存失效占位:缓存尚未接入,本次不做任何事");
    }

    private static String failureReason(Exception e) {
        String message = e.getMessage();
        return (message == null || message.isBlank()) ? e.getClass().getSimpleName() : message;
    }
}
