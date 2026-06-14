package com.vouchq.notify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MA3-85/MA3-92 default-off proof + DB-channel fan-out for {@link NotificationService}.
 *
 * <p>The critical self-hosted assertion (기획서 §7): with no <em>enabled</em> channel
 * row for the org (the default) dispatch makes <em>zero</em> outbound calls — here
 * proven by never invoking the adapter factory.
 */
class NotificationServiceTest {

    private static final UUID ORG = UUID.randomUUID();

    private static DriftNotification sample() {
        return new DriftNotification(ORG, UUID.randomUUID(), "greeter",
                UUID.randomUUID(), "CRITICAL", "approved-hash", "observed-hash");
    }

    private static NotificationChannelEntity channel(String name) {
        return new NotificationChannelEntity(UUID.randomUUID(), ORG,
                NotificationChannelEntity.Type.WEBHOOK, name, "https://example.test/hook", "{}", true);
    }

    @Test
    void makesNoCallWhenNoChannelsEnabled() {
        NotificationChannelRepository repo = mock(NotificationChannelRepository.class);
        ChannelAdapterFactory adapters = mock(ChannelAdapterFactory.class);
        when(repo.findByOrgIdAndEnabledTrueOrderByCreatedAtAsc(ORG)).thenReturn(List.of());
        NotificationService service = new NotificationService(repo, adapters);

        service.dispatch(sample());

        assertThat(service.enabledChannelCount(ORG)).isZero();
        verify(adapters, never()).build(any()); // no transport ever built
    }

    @Test
    void dispatchesToEveryEnabledChannel() {
        NotificationChannelRepository repo = mock(NotificationChannelRepository.class);
        ChannelAdapterFactory adapters = mock(ChannelAdapterFactory.class);
        NotificationChannelEntity a = channel("a");
        NotificationChannelEntity b = channel("b");
        when(repo.findByOrgIdAndEnabledTrueOrderByCreatedAtAsc(ORG)).thenReturn(List.of(a, b));

        RecordingChannel ra = new RecordingChannel("a");
        RecordingChannel rb = new RecordingChannel("b");
        when(adapters.build(a)).thenReturn(ra);
        when(adapters.build(b)).thenReturn(rb);

        NotificationService service = new NotificationService(repo, adapters);
        DriftNotification n = sample();
        service.dispatch(n);

        assertThat(ra.received).containsExactly(n);
        assertThat(rb.received).containsExactly(n);
    }

    @Test
    void oneFailingChannelDoesNotStopTheOthers() {
        NotificationChannelRepository repo = mock(NotificationChannelRepository.class);
        ChannelAdapterFactory adapters = mock(ChannelAdapterFactory.class);
        NotificationChannelEntity boomRow = channel("boom");
        NotificationChannelEntity okRow = channel("ok");
        when(repo.findByOrgIdAndEnabledTrueOrderByCreatedAtAsc(ORG)).thenReturn(List.of(boomRow, okRow));

        NotificationChannel boom = new NotificationChannel() {
            public String name() { return "boom"; }
            public void send(DriftNotification n) { throw new RuntimeException("transport down"); }
        };
        RecordingChannel ok = new RecordingChannel("ok");
        when(adapters.build(boomRow)).thenReturn(boom);
        when(adapters.build(okRow)).thenReturn(ok);

        NotificationService service = new NotificationService(repo, adapters);
        service.dispatch(sample()); // must not propagate the exception

        assertThat(ok.received).hasSize(1);
    }

    @Test
    void sendTestBuildsAndSendsThroughTheChannel() {
        NotificationChannelRepository repo = mock(NotificationChannelRepository.class);
        ChannelAdapterFactory adapters = mock(ChannelAdapterFactory.class);
        NotificationChannelEntity row = channel("c");
        RecordingChannel rc = new RecordingChannel("c");
        when(adapters.build(row)).thenReturn(rc);

        NotificationService service = new NotificationService(repo, adapters);
        service.sendTest(row);

        ArgumentCaptor<NotificationChannelEntity> cap = ArgumentCaptor.forClass(NotificationChannelEntity.class);
        verify(adapters, times(1)).build(cap.capture());
        assertThat(cap.getValue()).isSameAs(row);
        assertThat(rc.received).hasSize(1);
    }

    private static final class RecordingChannel implements NotificationChannel {
        private final String name;
        private final java.util.List<DriftNotification> received = new java.util.ArrayList<>();
        RecordingChannel(String name) { this.name = name; }
        public String name() { return name; }
        public void send(DriftNotification n) { received.add(n); }
    }
}
