package org.example.guide.controller;

import org.example.guide.pojo.dto.CrawlReport;
import org.example.guide.service.ICrawlerService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 采集的后台入口:管理员手动触发一次全量采集。
 *
 * 当前**无鉴权** —— Shiro 的过滤链现在是全放行(见 ShiroConfig),后台登录做好之后
 * /admin/** 自然收口,这里不用改。
 */
@RestController
@RequestMapping("/admin")
public class AdminCrawlController {

    private final ICrawlerService crawlerService;

    public AdminCrawlController(ICrawlerService crawlerService) {
        this.crawlerService = crawlerService;
    }

    /** 返回采集报告本身,不去套统一响应包:这是后台运维接口,不是小程序的稳定契约 */
    @PostMapping("/crawl")
    public CrawlReport crawl() {
        return crawlerService.crawlAll();
    }
}
