// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HttpEventsTest {

    @Test
    void no_subscribers_means_no_work_signal() {
        HttpEvents hub = new HttpEvents();
        assertThat(hub.hasSubscribers()).isFalse();
        try (HttpEvents.Subscription s = hub.subscribe()) {
            assertThat(hub.hasSubscribers()).isTrue();
        }
        assertThat(hub.hasSubscribers()).isFalse(); // close unregisters
    }

    @Test
    void frames_carry_monotonic_ids_and_sse_framing() throws Exception {
        HttpEvents hub = new HttpEvents();
        try (HttpEvents.Subscription s = hub.subscribe()) {
            hub.publish("request-start", JsonOut.object().put("requestId", 1));
            hub.publish("request-finish", JsonOut.object().put("requestId", 1));
            assertThat(s.next(1000))
                    .isEqualTo("id: 1\nevent: request-start\ndata: {\"requestId\":1}\n\n");
            assertThat(s.next(1000))
                    .isEqualTo("id: 2\nevent: request-finish\ndata: {\"requestId\":1}\n\n");
        }
    }

    @Test
    void every_subscriber_gets_every_frame() throws Exception {
        HttpEvents hub = new HttpEvents();
        try (HttpEvents.Subscription a = hub.subscribe();
                HttpEvents.Subscription b = hub.subscribe()) {
            hub.publish("module-start", JsonOut.object().put("dir", "/w/m1"));
            assertThat(a.next(1000)).contains("event: module-start");
            assertThat(b.next(1000)).contains("event: module-start");
        }
    }

    @Test
    void slow_subscriber_sheds_oldest_frames_never_blocks_the_publisher() throws Exception {
        HttpEvents hub = new HttpEvents();
        try (HttpEvents.Subscription s = hub.subscribe()) {
            int published = HttpEvents.QUEUE_CAPACITY + 10;
            for (int i = 1; i <= published; i++) {
                hub.publish("output", JsonOut.object().put("n", i)); // never blocks
            }
            List<String> received = new ArrayList<>();
            String frame;
            while ((frame = s.next(10)) != null) received.add(frame);
            assertThat(received).hasSize(HttpEvents.QUEUE_CAPACITY); // the oldest 10 were shed
            assertThat(received.getLast()).contains("\"n\":" + published); // newest survives
            assertThat(received.getFirst()).doesNotContain("\"n\":1}"); // oldest didn't
        }
    }

    @Test
    void frames_published_after_close_are_dropped() throws Exception {
        HttpEvents hub = new HttpEvents();
        HttpEvents.Subscription s = hub.subscribe();
        s.close();
        hub.publish("request-start", JsonOut.object().put("requestId", 1));
        assertThat(s.next(10)).isNull();
    }
}
