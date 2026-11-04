package dev.biribiri.elysium.activitypub.signature;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class HttpRequestDataTests {

    private static final byte[] BODY = {1, 2, 3};

    @Test
    void keepsTheTargetWithoutDecodingIt() {
        var request = new MockHttpServletRequest("POST", "/users/a%20b/inbox");
        request.setQueryString("x=%2F&y=1");

        assertThat(HttpRequestData.from(request, BODY).rawTarget()).isEqualTo("/users/a%20b/inbox?x=%2F&y=1");
    }

    @Test
    void hasNoQuestionMarkWithoutAQuery() {
        assertThat(HttpRequestData.from(new MockHttpServletRequest("GET", "/users/a"), BODY).rawTarget())
                .isEqualTo("/users/a");
    }

    @Test
    void keepsHeadersAsTheyWereSent() {
        var request = new MockHttpServletRequest("POST", "/inbox");
        request.addHeader("Content-Type", "application/activity+json");
        request.addHeader("X-Many", "one");
        request.addHeader("X-Many", "two");

        var data = HttpRequestData.from(request, BODY);

        assertThat(data.headers().getFirst("content-type")).isEqualTo("application/activity+json");
        assertThat(data.headers().get("x-many")).containsExactly("one", "two");
        assertThat(data.headers().containsHeader("Content-Length")).isFalse();
    }

    @Test
    void keepsTheMethodAndBody() {
        var data = HttpRequestData.from(new MockHttpServletRequest("POST", "/inbox"), BODY);

        assertThat(data.method()).isEqualTo("POST");
        assertThat(data.body()).isEqualTo(BODY);
    }
}
