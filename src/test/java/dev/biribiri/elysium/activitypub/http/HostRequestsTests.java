package dev.biribiri.elysium.activitypub.http;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HostRequestsTests {

    private final HostRequests requests = new HostRequests();

    @Test
    void deliveriesLeaveOneConnectionForAFetch() {
        for (var i = 0; i < HostRequests.MAX_DELIVERIES_PER_HOST; i++) {
            assertThat(requests.tryStartDelivery("busy.example")).isTrue();
        }

        assertThat(requests.tryStartDelivery("busy.example")).isFalse();
        assertThat(requests.tryStartFetch("busy.example")).isTrue();
    }

    @Test
    void fetchesAndDeliveriesTogetherStayWithinTheClientsConnections() {
        for (var i = 0; i < HostRequests.MAX_DELIVERIES_PER_HOST; i++) {
            assertThat(requests.tryStartDelivery("busy.example")).isTrue();
        }
        assertThat(requests.tryStartFetch("busy.example")).isTrue();

        assertThat(requests.tryStartFetch("busy.example")).isFalse();
    }

    @Test
    void fetchesCanKeepDeliveriesOut() {
        for (var i = 0; i < FederationHttpClient.MAX_CONNECTIONS_PER_HOST; i++) {
            assertThat(requests.tryStartFetch("busy.example")).isTrue();
        }

        assertThat(requests.tryStartDelivery("busy.example")).isFalse();
        requests.finish("busy.example");
        assertThat(requests.tryStartDelivery("busy.example")).isFalse();
        requests.finish("busy.example");
        assertThat(requests.tryStartDelivery("busy.example")).isTrue();
    }

    @Test
    void hostsDoNotAffectEachOther() {
        for (var i = 0; i < FederationHttpClient.MAX_CONNECTIONS_PER_HOST; i++) {
            requests.tryStartFetch("busy.example");
        }

        assertThat(requests.tryStartFetch("quiet.example")).isTrue();
        assertThat(requests.tryStartDelivery("quiet.example")).isTrue();
    }
}
