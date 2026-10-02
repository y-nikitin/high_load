package ua.edu.highload.common;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class InstanceIdFilterTest {
    @Test
    void identifiesServerEvenWhenClientSuppliesAnotherIdentityAndDoesNotCreateSession() throws Exception {
        var filter = new InstanceIdFilter("node-1");
        var request = new MockHttpServletRequest();
        request.addHeader("X-Instance-ID", "pretend-node-2");
        var response = new MockHttpServletResponse();
        FilterChain endpoint = (req, res) -> ((MockHttpServletResponse) res).setStatus(409);

        filter.doFilter(request, response, endpoint);

        assertThat(response.getHeader("X-Instance-ID")).isEqualTo("node-1");
        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(request.getSession(false)).isNull();
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    }

    @Test
    void fallbackIdentityIsStableForOneProcessAndDifferentForAnother() throws Exception {
        var first = new InstanceIdFilter("");
        var second = new InstanceIdFilter("");
        String firstId = identify(first);
        assertThat(identify(first)).isEqualTo(firstId);
        assertThat(identify(second)).isNotEqualTo(firstId);
    }

    @Test
    void rejectsUnsafeHeaderValuesAtStartup() {
        assertThatIllegalArgumentException().isThrownBy(() -> new InstanceIdFilter("node\r\nInjected: value"));
    }

    private String identify(InstanceIdFilter filter) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), response, (req, res) -> { });
        return response.getHeader("X-Instance-ID");
    }
}
