package cn.campus;

import static cn.campus.Store.p;
import static cn.campus.MarketService.require;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/** 物品检索、发布维护、认证材料、收藏与公开评价。 */
@RestController
@RequestMapping("/api")
public class ItemController {
    final MarketService service;
    final SessionSupport session;

    ItemController(MarketService service, SessionSupport session) {
        this.service = service;
        this.session = session;
    }

    @GetMapping("/items")
    Object items(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String category,
            @RequestParam(defaultValue = "1") int page) {
        require(q.length() <= 120, "搜索词过长。");
        return service.market(q, category, page);
    }

    @GetMapping("/items/mine")
    Object mine(HttpServletRequest r) {
        return service.mine(session.uid(r));
    }

    @PostMapping("/items")
    Object publish(@RequestBody Map<String, Object> b, HttpServletRequest r) {
        return service.saveItem(session.uid(r), null, b);
    }

    @PutMapping("/items/{id}")
    Object edit(@PathVariable String id, @RequestBody Map<String, Object> b, HttpServletRequest r) {
        return service.saveItem(session.uid(r), id, b);
    }

    @PostMapping("/items/{id}/withdraw")
    Object withdraw(@PathVariable String id, HttpServletRequest r) {
        service.withdraw(session.uid(r), id);
        return p("ok", true);
    }

    @GetMapping("/verifications")
    Object verifications(HttpServletRequest r) {
        return service.verifications(session.uid(r));
    }

    @PostMapping("/verifications")
    Object verify(@RequestBody Map<String, Object> b, HttpServletRequest r) {
        service.verify(session.uid(r), b);
        return p("ok", true);
    }

    @GetMapping("/favorites")
    Object favorites(HttpServletRequest r) {
        return service.favorites(session.uid(r));
    }

    @PostMapping("/favorites/{id}")
    Object favorite(@PathVariable String id, HttpServletRequest r) {
        service.favorite(session.uid(r), id);
        return p("ok", true);
    }

    @GetMapping("/sellers/{id}/reviews")
    Object reviews(@PathVariable String id) {
        return service.reviews(id);
    }
}
