package cn.campus;

import static cn.campus.Store.p;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/** 管理员审核、治理、争议结案与统计。治理身份不获得买卖双方的事实确认权。 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {
    final MarketService service;
    final SessionSupport session;

    AdminController(MarketService service, SessionSupport session) {
        this.service = service;
        this.session = session;
    }

    @GetMapping("/dashboard")
    Object dashboard(HttpServletRequest r) {
        return service.dashboard(session.uid(r));
    }

    @GetMapping("/stats")
    Object stats(@RequestParam(defaultValue = "") String from, @RequestParam(defaultValue = "") String to,
            HttpServletRequest r) {
        return service.statistics(session.uid(r), from, to);
    }

    @PostMapping("/{kind}/{id}")
    Object manage(@PathVariable String kind, @PathVariable String id, @RequestBody Map<String, Object> b,
            HttpServletRequest r) {
        service.manage(session.uid(r), kind, id, b);
        return p("ok", true);
    }
}
