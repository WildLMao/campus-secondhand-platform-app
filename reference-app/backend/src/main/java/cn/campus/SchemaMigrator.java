package cn.campus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * 版本化数据库迁移：按 V{n}__描述.sql 的顺序执行未应用的脚本，并把版本、描述、校验和、
 * 应用时间写入 schema_migrations。已应用脚本的内容被修改时启动失败，避免环境之间结构漂移。
 *
 * 空库初始化与后续升级共用同一机制；每次启动不再用 CREATE TABLE IF NOT EXISTS 猜测结构。
 * MySQL 的 DDL 不能回滚，脚本应保持幂等或可人工续做（见 db/migration/README.md）。
 */
@Component
@Order(0)
public class SchemaMigrator implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SchemaMigrator.class);
    private static final Pattern NAME = Pattern.compile("V(\\d+)__(.+)\\.sql");

    final DataSource dataSource;
    final List<String> locations;

    SchemaMigrator(DataSource dataSource,
            @Value("${app.migration.locations:classpath:db/migration}") List<String> locations) {
        this.dataSource = dataSource;
        this.locations = locations;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<Script> scripts = scripts();
        Connection connection;
        try {
            connection = dataSource.getConnection();
        } catch (SQLException e) {
            throw new IllegalStateException("无法连接数据库（仅支持 MySQL 8.4）。请检查 DB_URL/DB_USER/DB_PASSWORD；"
                    + "建库与验证步骤见 docs/database/mysql.md。", e);
        }
        try (Connection c = connection) {
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                createHistory(c);
                Map<Long, String> applied = applied(c);
                for (Script s : scripts) {
                    String checksum = applied.get(s.version);
                    if (checksum != null) {
                        if (!checksum.equals(s.checksum))
                            throw new IllegalStateException("迁移 V" + s.version + " 已执行，但文件内容被修改。"
                                    + "请恢复原脚本并新增 V" + (maxVersion(applied) + 1) + " 完成升级。");
                        log.info("迁移 V{} 已应用，跳过（{}）", s.version, s.description);
                        continue;
                    }
                    log.info("执行迁移 V{} · {}", s.version, s.description);
                    try (Statement st = c.createStatement()) {
                        for (String sql : split(s.body))
                            st.execute(sql);
                    }
                    record(c, s);
                    c.commit();
                }
                checkServer(c);
            } catch (Exception e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(autoCommit);
            }
        }
    }

    void createHistory(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("create table if not exists schema_migrations ("
                    + "version bigint not null primary key,"
                    + "description varchar(200) not null,"
                    + "checksum varchar(64) not null,"
                    + "applied_at varchar(35) not null)");
        }
    }

    Map<Long, String> applied(Connection c) throws SQLException {
        Map<Long, String> out = new HashMap<>();
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("select version,checksum from schema_migrations")) {
            while (rs.next())
                out.put(rs.getLong(1), rs.getString(2));
        }
        return out;
    }

    void record(Connection c, Script s) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "insert into schema_migrations(version,description,checksum,applied_at) values(?,?,?,?)")) {
            ps.setLong(1, s.version);
            ps.setString(2, s.description);
            ps.setString(3, s.checksum);
            ps.setString(4, MarketService.now());
            ps.executeUpdate();
        }
    }

    List<Script> scripts() throws Exception {
        List<Script> found = new ArrayList<>();
        Set<Long> versions = new HashSet<>();
        var resolver = new PathMatchingResourcePatternResolver(getClass().getClassLoader());
        for (String location : locations) {
            String base = location == null ? "" : location.trim();
            if (base.isEmpty())
                continue;
            String pattern = (base.endsWith("/") ? base : base + "/") + "V*__*.sql";
            for (Resource r : resolver.getResources(pattern)) {
                String name = r.getFilename();
                Matcher m = NAME.matcher(name == null ? "" : name);
                if (!m.matches())
                    throw new IllegalStateException("迁移文件命名无效（应为 V{n}__描述.sql）：" + name);
                long version = Long.parseLong(m.group(1));
                if (!versions.add(version))
                    throw new IllegalStateException("迁移版本 V" + version + " 重复配置。");
                String body;
                try (var in = r.getInputStream()) {
                    body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                found.add(new Script(version, m.group(2).replace('_', ' '), checksum(body), body));
            }
        }
        found.sort(Comparator.comparingLong(Script::version));
        if (found.isEmpty())
            throw new IllegalStateException("未找到任何迁移脚本，请检查 app.migration.locations=" + locations);
        return found;
    }

    static long maxVersion(Map<Long, String> applied) {
        return applied.keySet().stream().mapToLong(Long::longValue).max().orElse(0);
    }

    static String checksum(String body) throws Exception {
        String normalized = body.replace("\r\n", "\n").replace('\r', '\n');
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(normalized.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 按分号拆分语句，忽略字符串字面量与注释中的分号。迁移脚本只包含 DDL，不支持存储过程。
     */
    static List<String> split(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean single = false, quoted = false, lineComment = false, blockComment = false;
        for (int i = 0; i < script.length(); i++) {
            char ch = script.charAt(i);
            char next = i + 1 < script.length() ? script.charAt(i + 1) : '\0';
            if (lineComment) {
                if (ch == '\n') {
                    lineComment = false;
                    current.append('\n');
                }
                continue;
            }
            if (blockComment) {
                if (ch == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (!single && !quoted) {
                boolean boundary = i == 0 || Character.isWhitespace(script.charAt(i - 1));
                if (ch == '-' && next == '-' && boundary) {
                    lineComment = true;
                    i++;
                    continue;
                }
                if (ch == '#') {
                    lineComment = true;
                    continue;
                }
                if (ch == '/' && next == '*') {
                    blockComment = true;
                    i++;
                    continue;
                }
            }
            if (ch == '\'' && !quoted)
                single = !single;
            else if (ch == '"' && !single)
                quoted = !quoted;
            if (ch == ';' && !single && !quoted) {
                add(out, current);
                continue;
            }
            current.append(ch);
        }
        add(out, current);
        return out;
    }

    private static void add(List<String> out, StringBuilder current) {
        String sql = current.toString().trim();
        if (!sql.isEmpty())
            out.add(sql);
        current.setLength(0);
    }

    /** MySQL 环境核对字符集、排序规则与隔离级别；不一致时给出部署提示而不静默更改。 */
    void checkServer(Connection c) throws SQLException {
        DatabaseMetaData meta = c.getMetaData();
        String product = meta.getDatabaseProductName();
        log.info("数据库：{} {}", product, meta.getDatabaseProductVersion());
        if (product == null || !product.toLowerCase(Locale.ROOT).contains("mysql"))
            return;
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(
                        "select @@character_set_database,@@collation_database,@@transaction_isolation")) {
            if (!rs.next())
                return;
            String charset = rs.getString(1), collation = rs.getString(2), isolation = rs.getString(3);
            log.info("MySQL 字符集={} 排序规则={} 隔离级别={}", charset, collation, isolation);
            if (!"utf8mb4".equalsIgnoreCase(charset))
                log.warn("当前库字符集为 {}，建议按 docs/database/mysql.md 使用 utf8mb4。", charset);
            if (!"READ-COMMITTED".equalsIgnoreCase(isolation))
                log.warn("当前隔离级别为 {}，业务按 READ COMMITTED 设计并与 Hikari 配置对齐。", isolation);
        }
    }

    record Script(long version, String description, String checksum, String body) {
    }
}
