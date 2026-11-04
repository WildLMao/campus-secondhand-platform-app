package cn.campus;

import static cn.campus.Store.p;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/** 举报、物品咨询与订单会话、站内通知。会话不改变交易状态。 */
@RestController
@RequestMapping("/api")
public class CommunityController {
    final MarketService service;
    final SessionSupport session;
    final Store db;

    CommunityController(MarketService service, SessionSupport session, Store db) {
        this.service = service;
        this.session = session;
        this.db = db;
    }

    @GetMapping("/reports")
    Object reports(HttpServletRequest r) {
        return service.reports(session.uid(r));
    }

    @PostMapping("/reports")
    Object report(@RequestBody Map<String, Object> b, HttpServletRequest r) {
        service.report(session.uid(r), b);
        return p("ok", true);
    }

    @GetMapping("/messages")
    Object messages(@RequestParam String type, @RequestParam String object,
            @RequestParam(defaultValue = "") String peer, HttpServletRequest r) {
        return service.messages(session.uid(r), type, object, peer);
    }

    @PostMapping("/messages")
    Object message(@RequestParam String type, @RequestParam String object, @RequestParam(defaultValue = "") String peer,
            @RequestBody Map<String, Object> b, HttpServletRequest r) {
        service.message(session.uid(r), type, object, peer, b);
        return p("ok", true);
    }

    @GetMapping("/inbox")
    Object inbox(HttpServletRequest r) {
        return service.inbox(session.uid(r));
    }

    @GetMapping("/notifications")
    Object notifications(HttpServletRequest r) {
        return db.list("select * from notifications where user_id=#{p.u} order by created_at desc limit 100",
                p("u", session.uid(r)));
    }
}
