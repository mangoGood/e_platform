package com.ecommerce.product.util;

/**
 * 用户名脱敏工具。
 *
 * <h3>脱敏规则（全层级统一，L1/L2/L3 一视同仁）</h3>
 * 设用户名去除首尾空白后的<b>码点</b>长度为 {@code n}：
 * <ul>
 *   <li>{@code n <= 0}（null / 空白）→ {@code "匿名用户"}</li>
 *   <li>{@code n == 1} → 首字符 + 一个星号，如 {@code 张} → {@code 张*}</li>
 *   <li>{@code n == 2} → 首字符 + 一个星号，如 {@code 张三} → {@code 张*}</li>
 *   <li>{@code n >= 3} → <b>首字符 + (n-2) 个星号 + 尾字符</b>，
 *       如 {@code 张三丰} → {@code 张*丰}、{@code buyer1} → {@code b****1}</li>
 *   <li>星号数量封顶 {@link #MAX_STARS} 个，防止超长用户名把响应体撑爆</li>
 * </ul>
 *
 * <p><b>关于任务书里的 {@code buyer1 → 买***1}</b>：那是"语义示例"而非字面规则——
 * 服务端不可能把英文 {@code buyer} 翻译成中文"买"。本工具按上述<b>可确定性执行</b>的规则
 * 输出 {@code b****1}，脱敏强度一致（仅暴露首尾各一字符）。
 *
 * <p>按<b>码点</b>而非 {@code char} 处理，保证 emoji、生僻字等增补平面字符不会被劈成半个。
 */
public final class MaskUtil {

    /** 用户名缺失时的兜底展示名。 */
    public static final String ANONYMOUS_NAME = "匿名用户";

    /** 星号数量上限。 */
    private static final int MAX_STARS = 6;

    private MaskUtil() {
        throw new AssertionError("工具类不允许实例化");
    }

    /**
     * 对用户名做脱敏。
     *
     * @param username 原始用户名，可为 null
     * @return 脱敏后的展示名，永不为 null
     */
    public static String maskUsername(String username) {
        if (username == null) {
            return ANONYMOUS_NAME;
        }
        String trimmed = username.trim();
        if (trimmed.isEmpty()) {
            return ANONYMOUS_NAME;
        }

        int[] codePoints = trimmed.codePoints().toArray();
        int length = codePoints.length;
        if (length <= 2) {
            return new String(codePoints, 0, 1) + "*";
        }

        int starCount = Math.min(length - 2, MAX_STARS);
        StringBuilder builder = new StringBuilder(length);
        builder.appendCodePoint(codePoints[0]);
        for (int i = 0; i < starCount; i++) {
            builder.append('*');
        }
        builder.appendCodePoint(codePoints[length - 1]);
        return builder.toString();
    }
}
