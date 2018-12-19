package com.mageddo.kafka.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.Versioned;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mageddo.kafka.HeaderKeys;
import com.mageddo.kafka.KafkaUtils;
import com.mageddo.kafka.exception.KafkaPostException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.util.StopWatch;
import org.springframework.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static com.mageddo.kafka.RetryUtils.retryTemplate;
import static org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive;
import static org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization;

public class MessageSenderImpl implements MessageSender {

	private ThreadLocal<MessageStatus> messageStatusThreadLocal = ThreadLocal.withInitial(MessageStatus::new);
	private final Logger logger = LoggerFactory.getLogger(getClass());
	private KafkaTemplate<String, byte[]> kafkaTemplate;
	private final ObjectMapper objectMapper;

	public MessageSenderImpl(KafkaTemplate<String, byte[]> kafkaTemplate) {
		this(kafkaTemplate, new ObjectMapper());
	}

	public MessageSenderImpl(KafkaTemplate<String, byte[]> kafkaTemplate, ObjectMapper objectMapper) {
		this.kafkaTemplate = kafkaTemplate;
		this.objectMapper = objectMapper;
	}

	@Override
	public ListenableFuture<SendResult> send(ProducerRecord r) {

		if (!isSynchronizationActive()) {
			try {
				return kafkaTemplate.send(r);
			} catch (Exception e) {
				throw new KafkaPostException(e);
			}
		}

		final StopWatch stopWatch = new StopWatch();
		stopWatch.start();
		registerSynchronization(new TransactionSynchronizationAdapter() {
			@Override
			public void beforeCommit(boolean readOnly) {
				final StopWatch stopWatch = new StopWatch();
				stopWatch.start();
				final MessageStatus messageStatus = messageStatusThreadLocal.get();
				try {
					retryTemplate(30, 100, 1.5).execute(ctx -> {
							if (!messageStatus.allProcessed()) {
								try {
									messageStatus.getLastMessageSent().get();
								} catch (Exception e){
									throw new KafkaPostException(e);
								}
								throw new KafkaPostException(String.format("expected=%d, actual=%d", messageStatus.getSent(), messageStatus.getTotal()));
							}
							return null;
						});
					logger.info("m=send, status=committed, records={}, time={}", messageStatus.getSent(), stopWatch.getTotalTimeMillis());
				} catch (KafkaPostException e){
					logger.info("m=send, status=rollback, records={}, time={}", messageStatus.getSent(), stopWatch.getTotalTimeMillis());
					throw e;
				}
			}

			@Override
			public void afterCompletion(int status) {
				messageStatusThreadLocal.remove();
			}
		});

		final MessageStatus messageStatus = messageStatusThreadLocal.get();
		final ListenableFuture<SendResult> listenableFuture = kafkaTemplate.send(r);
		messageStatus.setLastMessageSent(listenableFuture);
		messageStatus.addSent();

		listenableFuture.addCallback(
		it -> {
			messageStatus.addSuccess();
		},
		throwable -> {
			messageStatus.addError();
		});
		return listenableFuture;
	}

	@Override
	public List<ListenableFuture<SendResult>> send(String topic, Collection list){
		final List<ListenableFuture<SendResult>> results = new ArrayList<>();
		for (Object o : list) {
			results.add(send(topic, o));
		}
		return results;
	}

	@Override
	public ListenableFuture<SendResult> send(String topic, Versioned o){
		return send(topic, null, o);
	}

	@Override
	public ListenableFuture<SendResult> send(String topic, String key, Versioned o){
		try {
			final ProducerRecord r = new ProducerRecord<>(topic, key, objectMapper.writeValueAsBytes(o));
			r.headers().add(new RecordHeader(HeaderKeys.VERSION, o.version().toFullString().getBytes()));
			return send(r);
		} catch (JsonProcessingException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public ListenableFuture<SendResult> send(String topic, String v){
		return send(new ProducerRecord(topic, v));
	}

	@Override
	public ListenableFuture<SendResult> send(String topic, ConsumerRecord r){
		return send(new ProducerRecord<>(topic, null, r.key(), r.value(), r.headers()));
	}

	@Override
	public ListenableFuture<SendResult> sendDLQ(ConsumerRecord r){
		return sendDLQ(KafkaUtils.getDLQ(r.topic()), r);
	}

	@Override
	public ListenableFuture<SendResult> sendDLQ(String dlqTopic, ConsumerRecord r){
		return send(new ProducerRecord<>(dlqTopic, null, r.key(), r.value(), r.headers()));
	}

	@Override
	public ListenableFuture<SendResult> send(String topic, Object v){
		try {
			return send(topic, null, v);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public ListenableFuture<SendResult> send(String topic, String key, Object v){
		try {
			return send(new ProducerRecord<>(topic, key, objectMapper.writeValueAsBytes(v)));
		} catch (JsonProcessingException e) {
			throw new RuntimeException(e);
		}
	}

}
