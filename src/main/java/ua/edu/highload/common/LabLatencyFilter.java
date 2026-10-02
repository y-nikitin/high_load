package ua.edu.highload.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Lab-only delay on one selected instance; health checks and writes are unaffected. */
@Component
@Profile("lab3")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class LabLatencyFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String slowInstance = request.getHeader("X-Lab-Slow-Instance");
        if ("GET".equals(request.getMethod()) && "/api/products".equals(request.getRequestURI())
                && slowInstance != null && slowInstance.equals(response.getHeader("X-Instance-ID"))) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                response.sendError(503, "Request interrupted");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
