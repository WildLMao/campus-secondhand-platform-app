package cn.campus;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class Store {
    final SqlMapper mapper;

    Store(SqlMapper mapper) {
        this.mapper = mapper;
    }

    static Map<String, Object> p(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2)
            m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    List<Map<String, Object>> list(String sql, Map<String, Object> p) {
        return mapper.query(sql, p).stream().map(row -> {
            Map<String, Object> r = new LinkedHashMap<>();
            row.forEach((k, v) -> r.put(k.toLowerCase(Locale.ROOT), v));
            return r;
        }).toList();
    }

    Map<String, Object> one(String sql, Map<String, Object> p) {
        var rows = list(sql, p);
        return rows.isEmpty() ? null : rows.get(0);
    }

    int exec(String sql, Map<String, Object> p) {
        return mapper.execute(sql, p);
    }
}
