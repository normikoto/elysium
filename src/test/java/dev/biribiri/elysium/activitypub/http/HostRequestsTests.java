package dev.biribiri.elysium.activitypub.http;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HostRequestsTests {

    private static final int MAX_FETCHES = FederationHttpClient.MAX_CONNECTIONS_PER_HOST;

    private final HostRequests requests = new HostRequests();

    @Test
    void limitsDeliveriesToOneHost() {
        for (var i = 0; i < HostRequests.MAX_DELIVERIES_PER_HOST; i++) {
            assertThat(requests.tryStartDelivery("busy.example")).isTrue();
        }

        assertThat(requests.tryStartDelivery("busy.example")).isFalse();
        requests.finishDelivery("busy.example");
        assertThat(requests.tryStartDelivery("busy.example")).isTrue();
    }

    @Test
    void deliveriesDoNotStopFetches() {
        for (var i = 0; i < HostRequests.MAX_DELIVERIES_PER_HOST; i++) {
            requests.tryStartDelivery("busy.example");
        }

        for (var i = 0; i < MAX_FETCHES; i++) {
            assertThat(requests.tryStartFetch("busy.example")).isTrue();
        }
        assertThat(requests.tryStartFetch("busy.example")).isFalse();
    }

    @Test
    void fetchesHoldDeliveriesBack() {
        for (var i = 0; i < HostRequests.MAX_DELIVERIES_PER_HOST; i++) {
            requests.tryStartFetch("busy.example");
        }

        assertThat(requests.tryStartDelivery("busy.example")).isFalse();
        requests.finishFetch("busy.example");
        assertThat(requests.tryStartDelivery("busy.example")).isTrue();
    }

    @Test
    void hostsDoNotAffectEachOther() {
        for (var i = 0; i < MAX_FETCHES; i++) {
            requests.tryStartFetch("busy.example");
        }

        assertThat(requests.tryStartFetch("quiet.example")).isTrue();
        assertThat(requests.tryStartDelivery("quiet.example")).isTrue();
    }
}
