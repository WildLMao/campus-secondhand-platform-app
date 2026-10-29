package cn.campus;

import static cn.campus.Store.p;
import static cn.campus.MarketService.*;
import java.util.*;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ApiController {
    final MarketService service;
    final Store db;

    ApiController(MarketService s, Store db) {
        this.service = s;
        this.db = db;
    }

    String uid(HttpServletRequest r) {
        var session = r.getSession(false);
        String id = session == null ? null : (String) session.getAttribute("uid");
        if (id == null)
            throw new ApiError(401, "请先登录。");
        service.account(id);
        return id;
    }

    String csrf(HttpSession s) {
        if (s.getAttribute("csrf") == null)
            s.setAttribute("csrf", id());
        return (String) s.getAttribute("csrf");
    }

    @GetMapping("/me")
    Object me(HttpServletRequest r) {
        var s = r.getSession();
        String u = (String) s.getAttribute("uid");
        return p("user", u == null ? null : service.publicUser(service.account(u)), "csrf", csrf(s));
    }

    @PostMapping("/auth/register")
    Object register(@RequestBody Map<String, Object> b, HttpServletRequest r) {
        var u = service.register(b);
        r.changeSessionId();
        r.getSession().setAttribute("uid", u.get("id"));
        r.getSession().setAttribute("csrf", id());
        return p("user", u, "csrf", csrf(r.getSession()));
    }

    @PostMapping("/auth/login")
    Object login(@RequestBody Map<String, Object> b, HttpServletRequest r) {
        var u = service.login(b);
        r.changeSessionId();
        r.getSession().setAttribute("uid", u.get("id"));
        r.getSession().setAttribute("csrf", id());
        return p("user", u, "csrf", csrf(r.getSession()));
    }

    @PostMapping("/auth/logout")
    Object logout(HttpServletRequest r) {
        r.getSession().invalidate();
        return p("ok", true);
    }

    @GetMapping("/items")
    Object items(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String category,
            @RequestParam(defaultValue = "1") int page) {
        require(q.length() <= 120, "搜索词过长。");
        return service.market(q, category, page);
    }

    @GetMapping("/items/mine")
    Object mine(HttpServletRequest r) {
        return service.mine(uid(r));
    }

    @PostMapping("/items")
    Object publish(@RequestBody Map<String, Object> b, HttpServletRequest r) {
        return service.saveItem(uid(r), null, b);
    }

    @PutMapping("/items/{id}")
    Object edit(@PathVariable String id, @RequestBody Map<String, Object> b, HttpServletRequest r) {
        return service.saveItem(uid(r), id, b);
    }

    @PostMapping("/items/{id}/withdraw")
    Object withdraw(@PathVariable String id, HttpServletRequest r) {
        service.withdraw(uid(r), id);
        return p("ok", true);
    }

    @GetMapping("/verifications")
    Object verifications(HttpServletRequest r) {
        return service.verifications(uid(r));
    }

    @PostMapping("/verifications")
    Object verify(@RequestBody Map<String, Object> b, HttpServletRequest r) {
        service.verify(uid(r), b);
        return p("ok", true);
    }

    @GetMapping("/orders")
    Object orders(HttpServletRequest r) {
        return service.orders(uid(r));
    }

    @PostMapping("/orders")
    Object order(@RequestBody Map<String, Object> b,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest r) {
        return service.createOrder(uid(r), b, key);
    }

    @PostMapping("/orders/{id}/{action:accept|reject|deliver|confirm|cancel|remind}")
    Object action(@PathVariable String id, @PathVariable String action, @RequestBody Map<String, Object> b,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest r) {
        return service.action(uid(r), id, action, b, key);
    }

    @GetMapping("/favorites")
    Object favorites(HttpServletRequest r) {
        return service.favorites(uid(r));
    }

    @PostMapping("/favorites/{id}")
    Object favorite(@PathVariable String id, HttpServletRequest r) {
        service.favorite(uid(r), id);
        return p("ok", true);
    }

    @GetMapping("/sellers/{id}/reviews")
    Object reviews(@PathVariable String id) {
        return service.reviews(id);
    }

    @GetMapping("/orders/{id}/reviews")
    Object orderReviews(@PathVariable String id, HttpServletRequest r) {
        return service.orderReviews(uid(r), id);
    }

    @PostMapping("/orders/{id}/reviews")
    Object review(@PathVariable String id, @RequestBody Map<String, Object> b, HttpServletRequest r) {
        service.review(uid(r), id, b);
        return p("ok", true);
    }

    @GetMapping("/disputes")
    Object disputes(HttpServletRequest r) {
        return service.disputes(uid(r));
    }

    @PostMapping("/orders/{id}/disputes")
    Object dispute(@PathVariable String id, @RequestBody Map<String, Object> b, HttpServletRequest r) {
        service.dispute(uid(r), id, b);
        return p("ok", true);
    }

    @PostMapping("/disputes/{id}/evidence")
    Object evidence(@PathVariable String id, @RequestBody Map<String, Object> b, HttpServletRequest r) {
        service.supplement(uid(r), id, b);
        return p("ok", true);
    }

    @GetMapping("/reports")
    Object reports(HttpServletRequest r) {
        return service.reports(uid(r));
    }

    @PostMapping("/reports")
    Object report(@RequestBody Map<String, Object> b, HttpServletRequest r) {
        service.report(uid(r), b);
        return p("ok", true);
    }

    @GetMapping("/messages")
    Object messages(@RequestParam String type, @RequestParam String object,
            @RequestParam(defaultValue = "") String peer, HttpServletRequest r) {
        return service.messages(uid(r), type, object, peer);
    }

    @PostMapping("/messages")
    Object message(@RequestParam String type, @RequestParam String object, @RequestParam(defaultValue = "") String peer,
            @RequestBody Map<String, Object> b, HttpServletRequest r) {
        service.message(uid(r), type, object, peer, b);
        return p("ok", true);
    }

    @GetMapping("/inbox")
    Object inbox(HttpServletRequest r) {
        return service.inbox(uid(r));
    }

    @GetMapping("/notifications")
    Object notifications(HttpServletRequest r) {
        return db.list("select * from notifications where user_id=#{p.u} order by created_at desc limit 100",
                p("u", uid(r)));
    }

    @GetMapping("/admin/dashboard")
    Object dashboard(HttpServletRequest r) {
        return service.dashboard(uid(r));
    }

    @GetMapping("/admin/stats")
    Object stats(@RequestParam(defaultValue = "") String from, @RequestParam(defaultValue = "") String to,
            HttpServletRequest r) {
        return service.statistics(uid(r), from, to);
    }

    @PostMapping("/admin/{kind}/{id}")
    Object manage(@PathVariable String kind, @PathVariable String id, @RequestBody Map<String, Object> b,
            HttpServletRequest r) {
        service.manage(uid(r), kind, id, b);
        return p("ok", true);
    }
}
