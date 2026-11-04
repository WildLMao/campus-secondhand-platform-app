package cn.campus;

import static cn.campus.Store.p;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 关闭演示种子时的启动检查：必须能用运行配置创建管理员，且不能留下无人管理的空库。
 * 每个场景使用独立的测试内存库（test scope，不参与应用运行），避免影响其他测试上下文。
 */
class StartupTest {

    SpringApplication app() {
        var app = new SpringApplication(MarketApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
        return app;
    }

    /** 命令行参数优先级最高，确保测试不落到 application.yml 的 MySQL 默认值。 */
    String[] args(String database, String username, String password) {
        return new String[] {
                "--spring.datasource.url=jdbc:h2:mem:" + database
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
                "--spring.datasource.username=sa",
                "--spring.datasource.password=",
                "--spring.datasource.driver-class-name=org.h2.Driver",
                "--app.seed=false",
                "--app.upload-dir=./target/startup-test-uploads",
                "--app.bootstrap-admin.username=" + username,
                "--app.bootstrap-admin.password=" + password
        };
    }

    @Test
    void createsAdminFromRuntimeConfigurationWhenSeedDisabled() {
        try (ConfigurableApplicationContext ctx = app().run(args("startup_boot", "ops_admin", "OpsAdminPass123"))) {
            var service = ctx.getBean(MarketService.class);
            var db = ctx.getBean(Store.class);
            var admin = service.login(p("username", "ops_admin", "password", "OpsAdminPass123"));
            assertEquals("ADMIN", MarketService.s(admin, "role"));
            assertEquals(1, MarketService.n(service.account(MarketService.s(admin, "id")), "verified"));
            assertNull(db.one("select id from accounts where username=#{p.u}", p("u", "buyer")),
                    "关闭演示种子时不应创建演示账户");
            assertNotNull(db.one("select id from audit_logs where action=#{p.a}", p("a", "初始化管理员")));
        }
    }

    @Test
    void refusesToStartWithoutAnyAdmin() {
        var error = assertThrows(Exception.class, () -> app().run(args("startup_empty", "", "")));
        Throwable cause = error;
        boolean explained = false;
        while (cause != null) {
            if (cause.getMessage() != null && cause.getMessage().contains("BOOTSTRAP_ADMIN_USERNAME"))
                explained = true;
            cause = cause.getCause();
        }
        assertTrue(explained, "启动失败信息应说明如何提供初始管理员：" + error);
    }

    @Test
    void rejectsDemoPasswordForBootstrapAdmin() {
        var error = assertThrows(Exception.class,
                () -> app().run(args("startup_demo_pw", "ops_admin", AdminBootstrap.DEMO_PASSWORD)));
        Throwable cause = error;
        boolean explained = false;
        while (cause != null) {
            if (cause.getMessage() != null && cause.getMessage().contains("不能使用演示密码"))
                explained = true;
            cause = cause.getCause();
        }
        assertTrue(explained, "启动失败信息应拒绝使用演示密码：" + error);
    }
}
