package org.example.guide.utils;

import java.util.regex.Pattern;

/**
 * 英文名 → 对外标识的字符白名单。
 *
 * <p>应用侧只有 slug 一个消费方（{@link ItemFields#slugOf} 在它之上再叠一次小写）。
 * 图标文件名走的是**同一套**规则，但实现不在 Java 侧 —— 落盘的是应用外面的采集脚本
 * （{@code guide/tools/item_icons.py}），它按同样的白名单改写文件名。
 * 两处各抄一遍正则迟早会分叉，而分叉的后果是"图标下得下来、slug 却取不到详情"
 * 这种只在少数名字上发作的错，所以改白名单要两边一起改。
 *
 * <p><b>为什么需要改写而不是照搬。</b>数据源上确实有一个叫 {@code File:Bugle?.png} 的文件,
 * 但 {@code ?} 既建不出 Windows 文件(Win32 保留字符),又会被 URL 当成 query 的起点,
 * 靠百分号编码也救不回来 —— 编码过的路径在服务端会被还原成 {@code ?},而磁盘上根本没有
 * 这个名字的文件。所以不安全字符统一换成下划线。
 *
 * <p>这条改写只对 134 个名字里的极少数生效,其余是恒等变换({@code Hot Dog} → {@code Hot_Dog})。
 */
public final class IconFileNames {

    /**
     * 允许出现的字符。
     *
     * 这个集合是两端约束的交集:URL 路径段里无需转义(所以 {@code icon} 列存的相对路径
     * 不用再做编码),同时又都是合法 Windows 文件名(所以磁盘上真的建得出来)。
     * 空格不在集合里 —— 它先被换成下划线,也就是 MediaWiki 自己的规范写法。
     */
    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9._'()-]");

    private IconFileNames() {
    }

    /** 按白名单改写过的名字,不含扩展名。 */
    public static String sanitize(String display) {
        return UNSAFE.matcher(display.replace(' ', '_')).replaceAll("_");
    }
}
