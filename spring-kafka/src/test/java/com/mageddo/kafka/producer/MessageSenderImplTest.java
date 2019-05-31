package com.mageddo.kafka.producer;

import com.mageddo.kafka.CommitPhase;
import com.mageddo.kafka.exception.KafkaPostException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TimeoutException;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jsonb.JsonbAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureTask;
import org.springframework.util.concurrent.SettableListenableFuture;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.transaction.interceptor.TransactionAspectSupport.currentTransactionStatus;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = {})
@ContextConfiguration(classes = MessageSenderImplTest.Conf.class)
@EnableAspectJAutoProxy(proxyTargetClass = true)
@EnableTransactionManagement
@SpringBootApplication(exclude = JsonbAutoConfiguration.class)
public class MessageSenderImplTest {

	@Autowired
	private KafkaTemplate kafkaTemplate;

	@Autowired
	private MessageSender messageSender;

	@Autowired
	private Conf conf;

	@Before
	public void before(){
		reset(kafkaTemplate);
	}

	@Test
	public void mustSendWithWithoutWaitMessageCommitWhenThereIsNoDatabaseTransaction() throws Exception {

		// arrange
		final ListenableFuture result = mock(ListenableFuture.class);
		doReturn(result).when(kafkaTemplate).send(any(ProducerRecord.class));

		// act
		final ListenableFuture<SendResult> listenableFuture = messageSender.send(new ProducerRecord("myTopic", "value"));

		// assert
		verify(listenableFuture, never()).get();

	}

	@Test
	public void mustSendAndWaitMessageCommitWhenTransactionIsActive() throws Exception {

		// arrange
		final SettableListenableFuture<SendResult> future = spy(new SettableListenableFuture());
		doReturn(future).when(kafkaTemplate).send(any(ProducerRecord.class));
		Executors.newSingleThreadScheduledExecutor().submit(() -> {
			sleep(500);
			future.set(new SendResult(null, null));
		});

		// act
		final ListenableFuture<SendResult> listenableFuture = conf.send();

		// assert
		verify(listenableFuture).get();
	}

	@Test
	public void mustThrowExceptionAndRollbackTransactionWhenKafkaPostFail() throws Exception {

		// arrange
		final SettableListenableFuture<SendResult> future = spy(new SettableListenableFuture());
		doThrow(TimeoutException.class).when(future).get();

		doReturn(future).when(kafkaTemplate).send(any(ProducerRecord.class));
		Executors.newSingleThreadScheduledExecutor().submit(() -> {
			sleep(500);
			future.setException(new TimeoutException("fail to post message"));
		});

		// act
		try {
			conf.send();
			fail("transaction must rollback");
		} catch (KafkaPostException e){
			// assert
			verify(future, times(1)).get();
		}
		
	}

	@Test
	public void mustRollbackTransactionWhenOneOfTheMessagesFailToSend() throws Exception {

		// arrange
		final SettableListenableFuture<SendResult> future = spy(new SettableListenableFuture());
		doThrow(TimeoutException.class).when(future).get();

		SettableListenableFuture future1 = new SettableListenableFuture();

		doReturn(future)
		.doReturn(future1)
		.when(kafkaTemplate).send(any(ProducerRecord.class));

		Executors.newSingleThreadScheduledExecutor().submit(() -> {
			sleep(500);
			future.setException(new TimeoutException("fail to post message"));
			future1.set(new SendResult(null, null));
		});

		// act
		try {
			conf.send(2);
			fail("transaction must rollback");
		} catch (KafkaPostException e){
			// assert
			assertEquals("an error occurred on message post, errors=1", e.getMessage());
			verify(future, never()).get();
		}

	}

	@Test
	@Transactional
	public void mustRegisterOneSynchronizationOnly()  {

		// arrange
		final SettableListenableFuture<SendResult> future = spy(new SettableListenableFuture());
		doReturn(future).when(kafkaTemplate).send(any(ProducerRecord.class));
		Executors.newSingleThreadScheduledExecutor().submit(() -> {
			sleep(500);
			future.set(new SendResult(null, null));
		});

		// act
		conf.send();
		conf.send();

		// assert

		assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
	}

	@Test
	public void mustNotPostMessageBecauseTransactionIsRollbackOnlyAndTheAfterCommitPhaseIsUtilized() throws Exception {
		// arrange

		// act
		final ListenableFuture<SendResult> listenableFuture = conf.sendPostCommitWithRollback();

		// assert
		verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
		try {
			listenableFuture.get().getProducerRecord();
			fail("method must throw exception");
		} catch (UnsupportedOperationException e){
			assertEquals("This is a fake record because the SendResult only will be generated after the transaction commit", e.getMessage());
		}
	}

	@Test
	public void mustPostMessageAndCheckWhenTransactionWasCommittedAndAfterCommitPhaseUtilized() throws Exception {
		// arrange
		final var expectedListenableFuture = spy(new ListenableFutureTask<>(() -> new SendResult(null, null)));
		doReturn(expectedListenableFuture).when(kafkaTemplate).send(any(ProducerRecord.class));

		// act
		Executors.newSingleThreadExecutor().submit(() -> {
			sleep(300);
			expectedListenableFuture.run();
			System.out.println("ran");
		});
		final var returnedListenableFuture = conf.sendMessagePostCommit();

		// assert
		verify(kafkaTemplate).send(any(ProducerRecord.class));
		verify(expectedListenableFuture, times(2)).get();
		try {
			returnedListenableFuture.get().getProducerRecord();
			fail("method must throw exception");
		} catch (UnsupportedOperationException e){
			assertEquals("This is a fake record because the SendResult only will be generated after the transaction commit", e.getMessage());
		}
	}


	@Test
	public void mustPostPostButNotCheckIfMessageWasPostBecauseTransactionIsRollbackOnlyAndTheBeforeCommitPhaseWasUtilized() throws Exception {

		// arrange
		doReturn(mock(ListenableFuture.class)).when(kafkaTemplate).send(any(ProducerRecord.class));

		// act
		final ListenableFuture<SendResult> listenableFuture = conf.sendWithRollback();

		// assert
		verify(listenableFuture, never()).get();
		verify(kafkaTemplate).send(any(ProducerRecord.class));
	}


	static class Conf {

		@Autowired
		private MessageSender messageSender;

		@Bean
		@Primary
		public KafkaTemplate kafkaTemplate(){
			return mock(KafkaTemplate.class);
		}

		@Bean
		@Primary
		public MessageSender messageSender(KafkaTemplate kafkaTemplate){
			return new MessageSenderImpl(kafkaTemplate);
		}

		@Transactional
		public void send(int times){
			for (int i = 0; i < times; i++) {
				messageSender.send(new ProducerRecord("myTopic", "value"));
			}
		}

		@Transactional
		public ListenableFuture send(){
			return messageSender.send(new ProducerRecord("myTopic", "value"));
		}

		@Transactional
		public ListenableFuture<SendResult> sendWithRollback() {
			currentTransactionStatus().setRollbackOnly();
			return send();
		}

		@Transactional
		public ListenableFuture<SendResult> sendPostCommitWithRollback() {
			currentTransactionStatus().setRollbackOnly();
			return messageSender.send(new ProducerRecord("myTopic", "value"), CommitPhase.AFTER_COMMIT);
		}

		@Transactional
		public ListenableFuture<SendResult> sendMessagePostCommit() {
			return messageSender.send(new ProducerRecord("my-topic", "my-message"), CommitPhase.AFTER_COMMIT);
		}
	}


	private void sleep(int millis) {
		try {
			Thread.sleep(millis);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
}
