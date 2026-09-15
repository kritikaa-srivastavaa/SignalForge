package com.kritika.signalforge.event;
import com.kritika.signalforge.observability.SignalForgeMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.kritika.signalforge.event.processing.EventProcessingService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.FixedBackOff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KafkaConsumerConfigTests {

	private final KafkaConsumerConfig config = new KafkaConsumerConfig();

	@Test
	void configuresTwoOneSecondRetriesAndOnePartitionDlt() {
		FixedBackOff backOff = config.eventRetryBackOff();
		assertThat(backOff.getInterval()).isEqualTo(1000);
		assertThat(backOff.getMaxAttempts()).isEqualTo(2);
		BackOffExecution execution = backOff.start();
		assertThat(execution.nextBackOff()).isEqualTo(1000);
		assertThat(execution.nextBackOff()).isEqualTo(1000);
		assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
		assertThat(config.eventsDeadLetterTopic().name()).isEqualTo("signalforge.events.dlt");
		assertThat(config.eventsDeadLetterTopic().numPartitions()).isEqualTo(1);
	}

	@Test
	void recoversOnlyOnThirdFailure() {
		DeadLetterPublishingRecoverer recoverer = mock(DeadLetterPublishingRecoverer.class);
		// Keep the production retry count, but eliminate waiting in this unit test.
		FixedBackOff backOff = config.eventRetryBackOff();
		backOff.setInterval(0);
		DefaultErrorHandler handler = config.eventErrorHandler(recoverer, backOff);
		ConsumerRecord<String, EventMessage> record = record();
		Consumer<?, ?> consumer = mock(Consumer.class);
		MessageListenerContainer container = mock(MessageListenerContainer.class);
		RuntimeException failure = new RuntimeException("Test failure");
		assertThat(handler.handleOne(failure, record, consumer, container)).isFalse();
		assertThat(handler.handleOne(failure, record, consumer, container)).isFalse();
		verifyNoInteractions(recoverer);
		assertThat(handler.handleOne(failure, record, consumer, container)).isTrue();
		verify(recoverer).accept(record, consumer, failure);
	}

	@Test
	void successfulRetryDoesNotRecover() {
		DeadLetterPublishingRecoverer recoverer = mock(DeadLetterPublishingRecoverer.class);
		FixedBackOff backOff = config.eventRetryBackOff();
		backOff.setInterval(0);
		DefaultErrorHandler handler = config.eventErrorHandler(recoverer, backOff);
		EventProcessingService service = mock(EventProcessingService.class);
		EventConsumer listener = new EventConsumer(service, new SignalForgeMetrics(new SimpleMeterRegistry()));
		ConsumerRecord<String, EventMessage> record = record();
		RuntimeException failure = new RuntimeException("First attempt");
		doThrow(failure).doNothing().when(service).process(record.value());
		assertThatThrownBy(() -> listener.consume(record.value())).isSameAs(failure);
		assertThat(handler.handleOne(failure, record, mock(Consumer.class),
				mock(MessageListenerContainer.class))).isFalse();
		listener.consume(record.value());
		verify(service, times(2)).process(record.value());
		verifyNoInteractions(recoverer);
		handler.clearThreadState();
	}

	@Test
	@SuppressWarnings({"unchecked", "rawtypes"})
	void publishesOriginalKeyAndValueToDlt() {
		KafkaTemplate<String, EventMessage> template = mock(KafkaTemplate.class);
		ProducerFactory<String, EventMessage> factory = mock(ProducerFactory.class);
		when(template.getProducerFactory()).thenReturn(factory);
		when(factory.getConfigurationProperties()).thenReturn(Map.of());
		when(template.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
		DeadLetterPublishingRecoverer recoverer = config.eventRecoverer(template);
		// Metadata lookup requires a broker; destination partition is tested below.
		recoverer.setVerifyPartition(false);
		ConsumerRecord<String, EventMessage> record = record();
		recoverer.accept(record, new RuntimeException("Exhausted"));

		ArgumentCaptor<ProducerRecord<String, EventMessage>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
		verify(template).send(sent.capture());
		assertThat(sent.getValue().topic()).isEqualTo(KafkaConsumerConfig.DLT_TOPIC);
		assertThat(sent.getValue().partition()).isZero();
		assertThat(sent.getValue().key()).isEqualTo(record.key());
		assertThat(sent.getValue().value()).isSameAs(record.value());
	}

	@Test
	@SuppressWarnings({"unchecked", "rawtypes"})
	void failedDltSendIsNotSuccessfulRecovery() {
		KafkaTemplate<String, EventMessage> template = mock(KafkaTemplate.class);
		ProducerFactory<String, EventMessage> factory = mock(ProducerFactory.class);
		when(template.getProducerFactory()).thenReturn(factory);
		when(factory.getConfigurationProperties()).thenReturn(Map.of());
		when(template.send(any(ProducerRecord.class)))
				.thenReturn(CompletableFuture.failedFuture(new RuntimeException("Broker unavailable")));
		DeadLetterPublishingRecoverer recoverer = config.eventRecoverer(template);
		recoverer.setVerifyPartition(false);
		assertThatThrownBy(() -> recoverer.accept(record(), new RuntimeException("Exhausted")))
				.isInstanceOf(RuntimeException.class);
	}

	private ConsumerRecord<String, EventMessage> record() {
		Instant time = Instant.parse("2026-09-14T12:00:00Z");
		EventMessage event = new EventMessage(new UUID(902, 1), "payment-service", "API_ERROR",
				"LOW", "Request failed", time, time);
		return new ConsumerRecord<>(EventPublisher.TOPIC, 0, 42, event.id().toString(), event);
	}
}
