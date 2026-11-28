package dev.biribiri.elysium.activitypub.http;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

    @Test
    void aDeliveryCanWaitForAnotherToFinish() throws Exception {
        for (var i = 0; i < HostRequests.MAX_DELIVERIES_PER_HOST; i++) {
            requests.tryStartDelivery("busy.example");
        }

        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var waiting = threads.submit(() -> requests.tryStartDelivery("busy.example", Duration.ofSeconds(10)));
            Thread.sleep(50);
            requests.finishDelivery("busy.example");

            assertThat(waiting).succeedsWithin(Duration.ofSeconds(5)).isEqualTo(true);
        }
    }

    @Test
    void onlySoManyDeliveriesCanWaitForOneHost() throws Exception {
        for (var i = 0; i < HostRequests.MAX_DELIVERIES_PER_HOST; i++) {
            requests.tryStartDelivery("busy.example");
        }

        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var waiting = new ArrayList<Future<Boolean>>();
            for (var i = 0; i < HostRequests.MAX_DELIVERIES_WAITING_PER_HOST; i++) {
                waiting.add(threads.submit(() -> requests.tryStartDelivery("busy.example", Duration.ofSeconds(2))));
            }
            Thread.sleep(200);
            var started = System.nanoTime();

            assertThat(requests.tryStartDelivery("busy.example", Duration.ofSeconds(2))).isFalse();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
            assertThat(requests.tryStartDelivery("quiet.example", Duration.ofSeconds(2))).isTrue();
            for (var future : waiting) {
                assertThat(future.get()).isFalse();
            }
        }
    }

    @Test
    void onlySoManyDeliveriesCanWaitForEveryHost() throws Exception {
        var hosts = HostRequests.MAX_DELIVERIES_WAITING / HostRequests.MAX_DELIVERIES_WAITING_PER_HOST;
        for (var host = 0; host <= hosts; host++) {
            for (var i = 0; i < HostRequests.MAX_DELIVERIES_PER_HOST; i++) {
                requests.tryStartDelivery("busy" + host + ".example");
            }
        }

        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var waiting = new ArrayList<Future<Boolean>>();
            for (var host = 0; host < hosts; host++) {
                var name = "busy" + host + ".example";
                for (var i = 0; i < HostRequests.MAX_DELIVERIES_WAITING_PER_HOST; i++) {
                    waiting.add(threads.submit(() -> requests.tryStartDelivery(name, Duration.ofSeconds(2))));
                }
            }
            Thread.sleep(200);
            var started = System.nanoTime();

            assertThat(requests.tryStartDelivery("busy" + hosts + ".example", Duration.ofSeconds(2))).isFalse();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
            for (var future : waiting) {
                assertThat(future.get()).isFalse();
            }
        }
    }
}
