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

    /** 管理台数据（仅管理员）：每项争议附带 evidence_log 补证记录，与当事人争议列表同一辅助方法生成。 */
    @GetMapping("/dashboard")
    Object dashboard(HttpServletRequest r) {
        return service.dashboard(session.uid(r));
    }

    /**
     * 统计时间参数以原始字符串接收（String，不声明为 Instant/Date 等类型参数），因此不与 Spring 的
     * 参数类型转换或应用默认时区发生关系；调用方写 Z、小数秒或 +08:00 等合法 ISO-8601 写法都能原样到达服务端。
     * 由 MarketService.statistics() 用 Instant.parse 统一解析为时刻，与订单完成时刻按时间比较，
     * 区间为 [from, to) 左闭右开；写法不同但表示同一时刻的结果一致。
     */
    @GetMapping("/stats")
    Object stats(@RequestParam(defaultValue = "") String from, @RequestParam(defaultValue = "") String to,
            HttpServletRequest r) {
        return service.statistics(session.uid(r), from, to);
    }

    /** 治理核查视图 {order,delivery,reviews}：仅限存在关联争议或订单举报的订单，查看留痕。 */
    @GetMapping("/orders/{id}/details")
    Object orderDetails(@PathVariable String id, HttpServletRequest r) {
        return service.adminOrderDetails(session.uid(r), id);
    }

    @PostMapping("/{kind}/{id}")
    Object manage(@PathVariable String kind, @PathVariable String id, @RequestBody Map<String, Object> b,
            HttpServletRequest r) {
        service.manage(session.uid(r), kind, id, b);
        return p("ok", true);
    }
}
