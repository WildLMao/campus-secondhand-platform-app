package cn.campus;

import static cn.campus.Store.p;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/**
 * 举报、物品咨询与订单会话、站内通知。会话不改变交易状态。
 *
 * MarketService 可能被 Spring 以 CGLIB 代理注入（例如代理其 public 业务方法），此时注入对象是代理壳：
 * 对它的字段直接访问读到的是代理壳中未初始化的 null 字段。因此这里只经 getter 调用通知补投，
 * 不经 service 字段直接访问任何成员，NotificationDelivery 本身也以构造注入获得。
 */
@RestController
@RequestMapping("/api")
public class CommunityController {
    final MarketService service;
    final NotificationDelivery notices;
    final SessionSupport session;
    final Store db;

    CommunityController(MarketService service, NotificationDelivery notices, SessionSupport session, Store db) {
        this.service = service;
        this.notices = notices;
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

    /** 会话上下文 {messages,readOnly}：终态订单返回 readOnly=true，发送仍由服务端拒绝。 */
    @GetMapping("/messages/context")
    Object context(@RequestParam String type, @RequestParam String object,
            @RequestParam(defaultValue = "") String peer, HttpServletRequest r) {
        return service.context(session.uid(r), type, object, peer);
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
        String uid = session.uid(r);
        // 先补投此前投递失败的待发送记录；失败只影响通知，不改变已成立的业务结果。
        // 经构造注入的 NotificationDelivery 调用（不经 MarketService 代理壳字段，也不被空指针悄悄跳过）。
        try {
            notices.deliverPending();
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(CommunityController.class).warn("补投通知失败：{}", e.getMessage());
        }
        return db.list("select * from notifications where user_id=#{p.u} order by created_at desc limit 100",
                p("u", uid));
    }
}
