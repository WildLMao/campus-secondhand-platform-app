package cn.campus;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class WebConfig extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("X-Frame-Options", "DENY");
        res.setHeader("Referrer-Policy", "same-origin");
        if (req.getRequestURI().startsWith("/api/")) {
            res.setHeader("Cache-Control", "no-store");
            if (!java.util.Set.of("GET", "HEAD", "OPTIONS").contains(req.getMethod())) {
                var s = req.getSession(false);
                Object token = s == null ? null : s.getAttribute("csrf");
                if (token == null || !token.equals(req.getHeader("X-CSRF-Token"))) {
                    res.setStatus(403);
                    res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write("{\"message\":\"会话校验失败，请刷新页面重试。\"}");
                    return;
                }
            }
        }
        chain.doFilter(req, res);
    }
}
