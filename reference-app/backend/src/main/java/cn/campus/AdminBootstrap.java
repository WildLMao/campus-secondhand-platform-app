package cn.campus;

import static cn.campus.Store.p;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 初始管理员：关闭演示种子时从运行配置读取凭据创建唯一管理员，避免形成无法管理的空库。
 * 已存在管理员时不做任何覆盖；不把演示密码当作正式初始化，也不把密码写入日志或仓库。
 */
@Component
@Order(50)
public class AdminBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    static final String DEMO_PASSWORD = "Campus123!";

    final Store db;
    final boolean seedEnabled;
    final String username;
    final String password;
    final String nickname;
    final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    AdminBootstrap(Store db, @Value("${app.seed}") boolean seedEnabled,
            @Value("${app.bootstrap-admin.username:}") String username,
            @Value("${app.bootstrap-admin.password:}") String password,
            @Value("${app.bootstrap-admin.nickname:校园管理员}") String nickname) {
        this.db = db;
        this.seedEnabled = seedEnabled;
        this.username = username;
        this.password = password;
        this.nickname = nickname;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (db.one("select id from accounts where role='ADMIN' limit 1", p()) != null) {
            log.info("已存在管理员账户，跳过初始管理员创建。");
            return;
        }
        String user = username == null ? "" : username.trim();
        String secret = password == null ? "" : password;
        boolean hasUser = !user.isEmpty(), hasSecret = !secret.isEmpty();
        if (!hasUser && !hasSecret) {
            if (seedEnabled && db.one("select id from accounts limit 1", p()) == null) {
                log.info("未配置初始管理员；演示种子将在本次启动创建仅限本地的演示账户。");
                return;
            }
            throw new IllegalStateException("数据库中没有管理员账户。请通过环境变量 BOOTSTRAP_ADMIN_USERNAME 与 "
                    + "BOOTSTRAP_ADMIN_PASSWORD 提供初始管理员，或在仅限本地的演示环境设置 DEMO_SEED=true。");
        }
        if (!hasUser || !hasSecret)
            throw new IllegalStateException("BOOTSTRAP_ADMIN_USERNAME 与 BOOTSTRAP_ADMIN_PASSWORD 必须同时提供。");
        if (!user.matches("[A-Za-z0-9_]{3,40}"))
            throw new IllegalStateException("BOOTSTRAP_ADMIN_USERNAME 使用 3—40 位字母、数字或下划线。");
        if (secret.equals(DEMO_PASSWORD))
            throw new IllegalStateException("BOOTSTRAP_ADMIN_PASSWORD 不能使用演示密码，请改用部署环境专用的强密码。");
        if (secret.length() < 12 || !secret.matches(".*[A-Za-z].*") || !secret.matches(".*\\d.*")
                || secret.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalStateException("BOOTSTRAP_ADMIN_PASSWORD 至少 12 位、同时包含字母和数字，且 UTF-8 不超过 72 字节。");
        String display = nickname == null || nickname.isBlank() ? "校园管理员" : nickname.trim();
        if (display.length() > 40)
            throw new IllegalStateException("BOOTSTRAP_ADMIN_NICKNAME 不能超过 40 字。");
        if (db.one("select id from accounts where username=#{p.u}", p("u", user)) != null)
            throw new IllegalStateException("用户名 " + user + " 已存在但不是管理员，请更换 BOOTSTRAP_ADMIN_USERNAME。");
        String uid = MarketService.id();
        db.exec("insert into accounts(id,username,password_hash,nickname,role,verified,restricted,created_at) "
                + "values(#{p.id},#{p.u},#{p.p},#{p.n},'ADMIN',1,0,#{p.t})",
                p("id", uid, "u", user, "p", encoder.encode(secret), "n", display, "t", MarketService.now()));
        db.exec("insert into audit_logs(id,actor_id,action,object_id,reason,created_at) "
                + "values(#{p.id},#{p.u},'初始化管理员',#{p.o},#{p.r},#{p.t})",
                p("id", MarketService.id(), "u", uid, "o", uid, "r", "由 BOOTSTRAP_ADMIN_* 运行配置创建", "t",
                        MarketService.now()));
        log.info("已创建初始管理员账户 {}。请在交付配置中移除明文密码并妥善保管凭据。", user);
    }
}
