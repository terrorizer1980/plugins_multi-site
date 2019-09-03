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

package com.googlesource.gerrit.plugins.multisite.consumer;

import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.server.events.EventGson;
import com.google.gson.Gson;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.multisite.InstanceId;
import com.googlesource.gerrit.plugins.multisite.MessageLogger;
import com.googlesource.gerrit.plugins.multisite.broker.BrokerApiWrapper;
import com.googlesource.gerrit.plugins.multisite.forwarder.events.EventTopic;
import com.googlesource.gerrit.plugins.multisite.forwarder.router.ProjectListUpdateRouter;
import java.util.UUID;

@Singleton
public class ProjectUpdateEventSubscriber extends AbstractSubcriber {
  @Inject
  public ProjectUpdateEventSubscriber(
      BrokerApiWrapper brokerApi,
      ProjectListUpdateRouter eventRouter,
      DynamicSet<DroppedEventListener> droppedEventListeners,
      @EventGson Gson gson,
      @InstanceId UUID instanceId,
      MessageLogger msgLog,
      SubscriberMetrics subscriberMetrics) {
    super(
        brokerApi, eventRouter, droppedEventListeners, gson, instanceId, msgLog, subscriberMetrics);
  }

  @Override
  protected EventTopic getTopic() {
    return EventTopic.PROJECT_LIST_TOPIC;
  }
}
