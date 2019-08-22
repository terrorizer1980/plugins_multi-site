// Copyright (C) 2019 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.multisite.kafka.consumer;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.util.ManualRequestContext;
import com.google.gerrit.server.util.OneOffRequestContext;
import com.google.gson.Gson;
import com.google.gwtorm.server.OrmException;
import com.googlesource.gerrit.plugins.multisite.InstanceId;
import com.googlesource.gerrit.plugins.multisite.MessageLogger;
import com.googlesource.gerrit.plugins.multisite.MessageLogger.Direction;
import com.googlesource.gerrit.plugins.multisite.broker.BrokerGson;
import com.googlesource.gerrit.plugins.multisite.consumer.SubscriberMetrics;
import com.googlesource.gerrit.plugins.multisite.forwarder.CacheNotFoundException;
import com.googlesource.gerrit.plugins.multisite.forwarder.events.EventTopic;
import com.googlesource.gerrit.plugins.multisite.forwarder.router.ForwardedEventRouter;
import com.googlesource.gerrit.plugins.multisite.kafka.KafkaConfiguration;
import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.Deserializer;

public abstract class AbstractKafkaSubcriber implements Runnable {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  private final Consumer<byte[], byte[]> consumer;
  private final ForwardedEventRouter eventRouter;
  private final DynamicSet<DroppedEventListener> droppedEventListeners;
  private final Gson gson;
  private final UUID instanceId;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final Deserializer<SourceAwareEventWrapper> valueDeserializer;
  private final KafkaConfiguration configuration;
  private final KafkaConsumerFactory consumerFactory;
  private final OneOffRequestContext oneOffCtx;
  private final MessageLogger msgLog;
  private SubscriberMetrics subscriberMetrics;

  public AbstractKafkaSubcriber(
      KafkaConfiguration configuration,
      KafkaConsumerFactory consumerFactory,
      Deserializer<byte[]> keyDeserializer,
      Deserializer<SourceAwareEventWrapper> valueDeserializer,
      ForwardedEventRouter eventRouter,
      DynamicSet<DroppedEventListener> droppedEventListeners,
      @BrokerGson Gson gson,
      @InstanceId UUID instanceId,
      OneOffRequestContext oneOffCtx,
      MessageLogger msgLog,
      SubscriberMetrics subscriberMetrics) {
    this.configuration = configuration;
    this.consumerFactory = consumerFactory;
    this.eventRouter = eventRouter;
    this.droppedEventListeners = droppedEventListeners;
    this.gson = gson;
    this.instanceId = instanceId;
    this.oneOffCtx = oneOffCtx;
    this.msgLog = msgLog;
    this.subscriberMetrics = subscriberMetrics;
    final ClassLoader previousClassLoader = Thread.currentThread().getContextClassLoader();
    try {
      Thread.currentThread().setContextClassLoader(AbstractKafkaSubcriber.class.getClassLoader());
      this.consumer = consumerFactory.create(keyDeserializer, instanceId);
    } finally {
      Thread.currentThread().setContextClassLoader(previousClassLoader);
    }
    this.valueDeserializer = valueDeserializer;
  }

  @Override
  public void run() {
    final String topic = configuration.getKafka().getTopicAlias(getTopic());
    subscribe(topic);
  }

  protected abstract EventTopic getTopic();

  public void subscribe(String topic) {
    try {

      logger.atInfo().log("Kafka consumer subscribing to topic alias [%s]", topic);
      consumer.subscribe(Collections.singleton(topic));
      while (!closed.get()) {
        ConsumerRecords<byte[], byte[]> consumerRecords =
            consumer.poll(Duration.ofMillis(configuration.kafkaSubscriber().getPollingInterval()));
        consumerRecords.forEach(
            consumerRecord -> {
              try (ManualRequestContext ctx = oneOffCtx.open()) {
                SourceAwareEventWrapper event =
                    valueDeserializer.deserialize(consumerRecord.topic(), consumerRecord.value());
                processRecord(event);
              } catch (Exception e) {
                logger.atSevere().withCause(e).log(
                    "Malformed event '%s': [Exception: %s]",
                    new String(consumerRecord.value(), UTF_8));
                subscriberMetrics.incrementSubscriberFailedToConsumeMessage();
              }
            });
      }
    } catch (WakeupException e) {
      // Ignore exception if closing
      if (!closed.get()) throw e;
    } catch (Exception e) {
      subscriberMetrics.incrementSubscriberFailedToPollMessages();
      throw e;
    } finally {
      consumer.close();
    }
  }

  private void processRecord(SourceAwareEventWrapper event) {

    if (event.getHeader().getSourceInstanceId().equals(instanceId)) {
      logger.atFiner().log(
          "Dropping event %s produced by our instanceId %s",
          event.toString(), instanceId.toString());
      droppedEventListeners.forEach(l -> l.onEventDropped(event));
    } else {
      try {
        msgLog.log(Direction.CONSUME, event);
        eventRouter.route(event.getEventBody(gson));
        subscriberMetrics.incrementSubscriberConsumedMessage();
      } catch (IOException e) {
        logger.atSevere().withCause(e).log(
            "Malformed event '%s': [Exception: %s]", event.getHeader().getEventType());
        subscriberMetrics.incrementSubscriberFailedToConsumeMessage();
      } catch (PermissionBackendException | OrmException | CacheNotFoundException e) {
        logger.atSevere().withCause(e).log(
            "Cannot handle message %s: [Exception: %s]", event.getHeader().getEventType());
        subscriberMetrics.incrementSubscriberFailedToConsumeMessage();
      }
    }
  }

  // Shutdown hook which can be called from a separate thread
  public void shutdown() {
    closed.set(true);
    consumer.wakeup();
  }
}
