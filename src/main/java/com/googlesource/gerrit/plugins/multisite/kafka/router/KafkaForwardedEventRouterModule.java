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

package com.googlesource.gerrit.plugins.multisite.kafka.router;

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.multisite.kafka.KafkaConfiguration;
import com.googlesource.gerrit.plugins.multisite.kafka.consumer.KafkaConsumerModule;

public class KafkaForwardedEventRouterModule extends AbstractModule {
  private KafkaConfiguration kafkaConfig;
  private KafkaConsumerModule kafkaConsumerModule;

  @Inject
  public KafkaForwardedEventRouterModule(
      KafkaConfiguration config, KafkaConsumerModule kafkaConsumerModule) {
    this.kafkaConfig = config;
    this.kafkaConsumerModule = kafkaConsumerModule;
  }

  @Override
  protected void configure() {
    if (kafkaConfig.kafkaSubscriber().enabled()) {
      install(kafkaConsumerModule);
    }
  }
}
