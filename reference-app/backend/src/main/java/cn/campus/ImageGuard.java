package cn.campus;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 公开文本的联系方式防护：评价可能公开展示，不能出现手机号、邮箱或 QQ/微信账号。
 *
 * 判定只针对“看起来像联系方式”的片段，避免误拦正常描述：
 * - 手机号：11 位（首位 1、次位 3—9），允许中间夹空格/短横线/括号，允许 +86/0086 前缀；
 *   片段前后不能紧贴数字或字母，因此“电话 13800138000.”会被识别，“iPhone13”“小米14”中的数字不会。
 * - 尾点等标点不能绕过：分隔符不含句点，句点只作为边界。
 * - 合法描述“手机 iPhone13，成色很好”“2024年购”不含 11 位号码，不会被拦截。
 * 读取历史公开评价时同样经过 mask()，防止修复前写入的内容继续公开。
 */
final class ImageGuard {
    /** 内部允许的分隔：空白、短横线、括号、斜杠；不含句点。 */
    private static final String INNER = "[\\s\\-()（）/]?";
    private static final String SEP = "[\\s\\-()（）/]";
    private static final Pattern MOBILE = Pattern.compile(
            "(?<![\\dA-Za-z])(?:(?:\\+|00)86" + SEP + "?)?" + "1[3-9]" + "(?:" + INNER + "\\d){9}(?![\\dA-Za-z])");
    private static final Pattern LANDLINE = Pattern.compile("(?<![\\dA-Za-z])0\\d{2,3}-?\\d{7,8}(?![\\dA-Za-z])");
    private static final Pattern EMAIL = Pattern.compile(
            "(?<![A-Za-z0-9._%+\\-])[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final String SPACED_MOBILE = "1\\s*[3-9](?:\\s*\\d){9}";
    private static final Pattern SOCIAL = Pattern.compile(
            "(?i)(?:微信|威信|v信|vx|wechat|qq|扣扣|企鹅)\\s*(?:号|号码)?\\s*[:：=]?\\s*[A-Za-z0-9_\\-]{5,20}");
    private static final Pattern IMAGE_LINK = Pattern.compile(
            "(?i)^https?:\\/\\/\\S+\\.(?:jpg|jpeg|png|webp|gif)(?:\\?\\S*)?$");

    static boolean contact(String text) {
        if (text == null || text.isBlank())
            return false;
        return MOBILE.matcher(text).find() || LANDLINE.matcher(text).find() || EMAIL.matcher(text).find()
                || socialHit(text);
    }

    /** 将文本中的联系方式替换为占位说明。 */
    static String mask(String text) {
        if (text == null || text.isBlank())
            return text;
        String out = MOBILE.matcher(text).replaceAll("[联系方式已隐藏]");
        out = LANDLINE.matcher(out).replaceAll("[联系方式已隐藏]");
        out = EMAIL.matcher(out).replaceAll("[联系方式已隐藏]");
        return replaceSocial(out);
    }

    private static boolean socialHit(String text) {
        Matcher m = SOCIAL.matcher(text);
        while (m.find())
            if (!IMAGE_LINK.matcher(m.group()).matches())
                return true;
        return false;
    }

    private static String replaceSocial(String text) {
        Matcher m = SOCIAL.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            if (IMAGE_LINK.matcher(m.group()).matches()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            } else {
                m.appendReplacement(sb, "[联系方式已隐藏]");
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static boolean spacedMobile(String text) {
        return text != null && text.matches(".*" + SPACED_MOBILE + ".*");
    }
}
