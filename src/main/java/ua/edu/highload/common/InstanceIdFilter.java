package ua.edu.highload.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Immutable diagnostic identity, never used to route requests or look up business state. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InstanceIdFilter extends OncePerRequestFilter {
    private final String instanceId;

    public InstanceIdFilter(@Value("${app.instance-id:}") String configuredId) {
        String candidate = configuredId.isBlank() ? "node-" + UUID.randomUUID() : configuredId;
        if (!candidate.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("INSTANCE_ID must contain 1-64 letters, digits, dots, underscores or hyphens");
        }
        this.instanceId = candidate;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // Deliberately ignore any client-supplied X-Instance-ID and never create an HTTP session.
        response.setHeader("X-Instance-ID", instanceId);
        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }
}
