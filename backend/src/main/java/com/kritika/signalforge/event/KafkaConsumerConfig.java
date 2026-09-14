package com.kritika.signalforge.event;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConsumerConfig {

	public static final String DLT_TOPIC = EventPublisher.TOPIC + ".dlt";
	private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

	@Bean
	public NewTopic eventsDeadLetterTopic() {
		return new NewTopic(DLT_TOPIC, 1, (short) 1);
	}

	@Bean
	public FixedBackOff eventRetryBackOff() {
		return new FixedBackOff(1000L, 2L);
	}

	@Bean
	public DeadLetterPublishingRecoverer eventRecoverer(KafkaTemplate<String, EventMessage> template) {
		DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template, (record, exception) -> {
			log.warn("Event processing failed after retries; publishing to DLT eventId={}",
					record.value() instanceof EventMessage event ? event.id() : record.key());
			return new TopicPartition(DLT_TOPIC, 0);
		});
		// A failed DLT send must not be treated as successful recovery.
		recoverer.setFailIfSendResultIsError(true);
		return recoverer;
	}

	@Bean
	public DefaultErrorHandler eventErrorHandler(DeadLetterPublishingRecoverer eventRecoverer,
			FixedBackOff eventRetryBackOff) {
		DefaultErrorHandler handler = new DefaultErrorHandler(eventRecoverer, eventRetryBackOff);
		// Changing exception types must not restart the retry budget.
		handler.setResetStateOnExceptionChange(false);
		return handler;
	}
}
