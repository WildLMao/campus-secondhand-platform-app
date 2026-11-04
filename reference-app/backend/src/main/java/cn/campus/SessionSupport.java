package cn.campus;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;

/**
 * 会话与 CSRF 的统一入口：所有控制器使用同一套身份读取与令牌生成逻辑。
 * 身份失效立即返回 401，避免各控制器各自解释 Session。
 */
@Component
public class SessionSupport {
    static final String UID = "uid";
    static final String CSRF = "csrf";

    final MarketService service;

    SessionSupport(MarketService service) {
        this.service = service;
    }

    String uid(HttpServletRequest r) {
        HttpSession session = r.getSession(false);
        String id = session == null ? null : (String) session.getAttribute(UID);
        if (id == null)
            throw new ApiError(401, "请先登录。");
        service.account(id);
        return id;
    }

    String csrf(HttpSession s) {
        if (s.getAttribute(CSRF) == null)
            s.setAttribute(CSRF, MarketService.id());
        return (String) s.getAttribute(CSRF);
    }

    /** 登录与注册后更换会话标识并重新签发 CSRF，防止会话固定。 */
    void signIn(HttpServletRequest r, String uid) {
        r.changeSessionId();
        r.getSession().setAttribute(UID, uid);
        r.getSession().setAttribute(CSRF, MarketService.id());
    }
}
