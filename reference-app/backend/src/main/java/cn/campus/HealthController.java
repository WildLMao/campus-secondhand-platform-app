package cn.campus;

import static cn.campus.Store.p;
import java.sql.Connection;
import javax.sql.DataSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行健康检查：供部署方确认应用、数据库连接与迁移版本。
 * 不返回主机、账号、连接串或凭据；数据库不可用时返回 503。
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {
    final DataSource dataSource;
    final Store db;

    HealthController(DataSource dataSource, Store db) {
        this.dataSource = dataSource;
        this.db = db;
    }

    @GetMapping
    Object health() {
        String database;
        long schema = 0;
        try (Connection c = dataSource.getConnection()) {
            database = c.getMetaData().getDatabaseProductName();
            for (var row : db.list("select version from schema_migrations", p()))
                schema = Math.max(schema, MarketService.n(row, "version"));
        } catch (Exception e) {
            throw new ApiError(503, "数据库暂不可用，请稍后重试。");
        }
        return p("status", "UP", "database", database, "schema", schema == 0 ? "" : "V" + schema,
                "checkedAt", MarketService.now());
    }
}
