package cn.campus;

import static cn.campus.Store.p;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/** 会话、注册与登录。CSRF 令牌随 /api/me、注册和登录响应下发。 */
@RestController
@RequestMapping("/api")
public class AuthController {
    final MarketService service;
    final SessionSupport session;

    AuthController(MarketService service, SessionSupport session) {
        this.service = service;
        this.session = session;
    }

    @GetMapping("/me")
    Object me(HttpServletRequest r) {
        var s = r.getSession();
        String uid = (String) s.getAttribute(SessionSupport.UID);
        return p("user", uid == null ? null : service.publicUser(service.account(uid)), "csrf", session.csrf(s));
    }

    @PostMapping("/auth/register")
    Object register(@RequestBody Map<String, Object> b, HttpServletRequest r) {
        var user = service.register(b);
        session.signIn(r, (String) user.get("id"));
        return p("user", user, "csrf", session.csrf(r.getSession()));
    }

    @PostMapping("/auth/login")
    Object login(@RequestBody Map<String, Object> b, HttpServletRequest r) {
        var user = service.login(b);
        session.signIn(r, (String) user.get("id"));
        return p("user", user, "csrf", session.csrf(r.getSession()));
    }

    @PostMapping("/auth/logout")
    Object logout(HttpServletRequest r) {
        var s = r.getSession(false);
        if (s != null)
            s.invalidate();
        return p("ok", true);
    }
}
