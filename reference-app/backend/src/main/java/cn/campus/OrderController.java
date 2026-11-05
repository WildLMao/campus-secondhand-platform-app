package cn.campus;

import static cn.campus.Store.p;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/** 订单阶段操作、评价与履约争议。订单状态与占用只在 MarketService 的统一入口变化。 */
@RestController
@RequestMapping("/api")
public class OrderController {
    final MarketService service;
    final SessionSupport session;

    OrderController(MarketService service, SessionSupport session) {
        this.service = service;
        this.session = session;
    }

    @GetMapping("/orders")
    Object orders(HttpServletRequest r) {
        return service.orders(session.uid(r));
    }

    @PostMapping("/orders")
    Object order(@RequestBody Map<String, Object> b,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest r) {
        return service.createOrder(session.uid(r), b, key);
    }

    @PostMapping("/orders/{id}/{action:accept|reject|deliver|confirm|cancel|remind}")
    Object action(@PathVariable String id, @PathVariable String action, @RequestBody Map<String, Object> b,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest r) {
        return service.action(session.uid(r), id, action, b, key);
    }

    /** 当事人订单详情：{order, delivery, reviews}；交付登记含 actor_id/note/created_at。 */
    @GetMapping("/orders/{id}/details")
    Object details(@PathVariable String id, HttpServletRequest r) {
        return service.orderDetails(session.uid(r), id);
    }

    @GetMapping("/orders/{id}/reviews")
    Object orderReviews(@PathVariable String id, HttpServletRequest r) {
        return service.orderReviews(session.uid(r), id);
    }

    @PostMapping("/orders/{id}/reviews")
    Object review(@PathVariable String id, @RequestBody Map<String, Object> b, HttpServletRequest r) {
        service.review(session.uid(r), id, b);
        return p("ok", true);
    }

    @GetMapping("/disputes")
    Object disputes(HttpServletRequest r) {
        return service.disputes(session.uid(r));
    }

    @PostMapping("/orders/{id}/disputes")
    Object dispute(@PathVariable String id, @RequestBody Map<String, Object> b, HttpServletRequest r) {
        service.dispute(session.uid(r), id, b);
        return p("ok", true);
    }

    @PostMapping("/disputes/{id}/evidence")
    Object evidence(@PathVariable String id, @RequestBody Map<String, Object> b, HttpServletRequest r) {
        service.supplement(session.uid(r), id, b);
        return p("ok", true);
    }
}
