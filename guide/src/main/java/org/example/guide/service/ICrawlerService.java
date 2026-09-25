package org.example.guide.service;

import org.example.guide.pojo.dto.CrawlReport;

/**
 * 爬虫采集:从数据源拉全量结构化数据 → 转换成图鉴条目 → 落库。
 *
 * 这是管理员后台的离线功能,小程序运行时永远不会触发它。
 */
public interface ICrawlerService {

    /**
     * 跑一次全量采集。
     *
     * 单条失败只跳过那一条,不影响整批;库里已存在的条目按 nameEn 更新,不做反删。
     *
     * @return 采集报告,含拉取行数、落库成功数、失败明细与未知字典值警告
     */
    CrawlReport crawlAll();
}
