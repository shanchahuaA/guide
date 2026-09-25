package org.example.guide.crawler;

import java.util.regex.Pattern;

/**
 * 图标文件名规则:条目英文名 → 数据源上的文件标题 / 本地文件名。
 *
 * 数据源的图片命名约定是 {@code File:<页面名>.png},3 个毒蘑菇变体的文件按 display 名命名
 * (如 {@code File:Bugle Shroom (Poisonous).png}),所以这里统一拿 display 推文件名,
 * 变体不用特判。
 *
 * <p><b>为什么本地文件名不总是等于数据源上的文件名。</b>
 * 数据源上确实有一个叫 {@code File:Bugle?.png} 的文件,但 {@code ?} 既建不出 Windows 文件
 * (Win32 保留字符),又会被 URL 当成 query 的起点,靠百分号编码也救不回来 ——
 * 编码过的路径在服务端会被还原成 {@code ?},而磁盘上根本没有这个名字的文件。
 * 所以本地名把不安全字符统一换成下划线。
 *
 * <p>这条改写只对 134 个名字里的极少数生效,其余是恒等变换
 * ({@code Hot Dog} → {@code Hot_Dog.png}),改写发生时 {@link #needsRewrite} 为真,
 * 采集侧会打一条日志,让实跑时的意外看得见。
 */
public final class IconFileNames {

    /**
     * 本地文件名允许出现的字符。
     *
     * 这个集合是两端约束的交集:URL 路径段里无需转义(所以 {@code icon} 列存的相对路径
     * 不用再做编码),同时又都是合法 Windows 文件名(所以磁盘上真的建得出来)。
     * 空格不在集合里 —— 它先被换成下划线,也就是 MediaWiki 自己的规范写法。
     */
    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9._'()-]");

    /** 数据源的图片命名空间 */
    private static final String FILE_NAMESPACE = "File:";

    private static final String SUFFIX = ".png";

    private IconFileNames() {
    }

    /**
     * 数据源上的文件标题,用于 imageinfo 查询。
     *
     * 空格保持原样即可:MediaWiki 把标题里的空格和下划线视为同一个字符,不必自己先转换。
     */
    public static String wikiTitle(String display) {
        return FILE_NAMESPACE + display + SUFFIX;
    }

    /**
     * 本地文件名,同时也是 {@code icon} 列里 URL 路径的最后一段。
     *
     * @return 形如 {@code Hot_Dog.png} / {@code Bugle Shroom (Poisonous).png} / {@code Bugle_.png}
     */
    public static String fileName(String display) {
        return UNSAFE.matcher(display.replace(' ', '_')).replaceAll("_") + SUFFIX;
    }

    /** 文件名里含有被换掉的字符时为 true */
    public static boolean needsRewrite(String display) {
        return !fileName(display).equals(display.replace(' ', '_') + SUFFIX);
    }
}
