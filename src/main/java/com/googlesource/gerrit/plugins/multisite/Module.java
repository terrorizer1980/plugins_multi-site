// Copyright (C) 2015 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.multisite;

import com.google.common.annotations.VisibleForTesting;
import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.gerrit.server.config.SitePaths;
import com.google.gson.Gson;
import com.google.inject.CreationException;
import com.google.inject.Inject;
import com.google.inject.Provides;
import com.google.inject.ProvisionException;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import com.google.inject.spi.Message;
import com.googlesource.gerrit.plugins.multisite.broker.BrokerApi;
import com.googlesource.gerrit.plugins.multisite.broker.BrokerGson;
import com.googlesource.gerrit.plugins.multisite.broker.BrokerModule;
import com.googlesource.gerrit.plugins.multisite.broker.GsonProvider;
import com.googlesource.gerrit.plugins.multisite.broker.kafka.KafkaBrokerForwarderModule;
import com.googlesource.gerrit.plugins.multisite.cache.CacheModule;
import com.googlesource.gerrit.plugins.multisite.event.EventModule;
import com.googlesource.gerrit.plugins.multisite.forwarder.ForwarderModule;
import com.googlesource.gerrit.plugins.multisite.forwarder.router.RouterModule;
import com.googlesource.gerrit.plugins.multisite.index.IndexModule;
import com.googlesource.gerrit.plugins.multisite.kafka.KafkaBrokerApi;
import com.googlesource.gerrit.plugins.multisite.kafka.router.KafkaForwardedEventRouterModule;
import com.googlesource.gerrit.plugins.multisite.validation.ValidationModule;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Module extends LifecycleModule {
  private static final Logger log = LoggerFactory.getLogger(Module.class);
  private Configuration config;
  private NoteDbStatus noteDb;
  private KafkaForwardedEventRouterModule kafkaForwardedEventRouterModule;
  private KafkaBrokerForwarderModule kafkaBrokerForwarderModule;
  private final boolean disableGitRepositoryValidation;

  @Inject
  public Module(
      Configuration config,
      NoteDbStatus noteDb,
      KafkaForwardedEventRouterModule forwardedEeventRouterModule,
      KafkaBrokerForwarderModule brokerForwarderModule) {
    this(config, noteDb, forwardedEeventRouterModule, brokerForwarderModule, false);
  }

  // TODO: It is not possible to properly test the libModules in Gerrit.
  // Disable the Git repository validation during integration test and then build the necessary
  // support
  // in Gerrit for it.
  @VisibleForTesting
  public Module(
      Configuration config,
      NoteDbStatus noteDb,
      KafkaForwardedEventRouterModule forwardedEeventRouterModule,
      KafkaBrokerForwarderModule brokerForwarderModule,
      boolean disableGitRepositoryValidation) {
    init(config, noteDb, forwardedEeventRouterModule, brokerForwarderModule);
    this.disableGitRepositoryValidation = disableGitRepositoryValidation;
  }

  private void init(
      Configuration config,
      NoteDbStatus noteDb,
      KafkaForwardedEventRouterModule forwardedEeventRouterModule,
      KafkaBrokerForwarderModule brokerForwarderModule) {
    this.config = config;
    this.noteDb = noteDb;
    this.kafkaForwardedEventRouterModule = forwardedEeventRouterModule;
    this.kafkaBrokerForwarderModule = brokerForwarderModule;
  }

  @Override
  protected void configure() {
    if (!noteDb.enabled()) {
      throw new ProvisionException(
          "Gerrit is still running on ReviewDb: please migrate to NoteDb "
              + "and then reload the multi-site plugin.");
    }

    Collection<Message> validationErrors = config.validate();
    if (!validationErrors.isEmpty()) {
      throw new CreationException(validationErrors);
    }

    listener().to(Log4jMessageLogger.class);
    bind(MessageLogger.class).to(Log4jMessageLogger.class);

    install(new ForwarderModule());

    if (config.cache().synchronize()) {
      install(new CacheModule());
    }
    if (config.event().synchronize()) {
      install(new EventModule());
    }
    if (config.index().synchronize()) {
      install(new IndexModule());
    }

    install(new BrokerModule());
    DynamicItem.bind(binder(), BrokerApi.class).to(KafkaBrokerApi.class).in(Scopes.SINGLETON);
    listener().to(KafkaBrokerApi.class);

    install(new RouterModule());

    install(kafkaForwardedEventRouterModule);
    install(kafkaBrokerForwarderModule);

    install(
        new ValidationModule(
            config, disableGitRepositoryValidation || !config.getSharedRefDb().isEnabled()));

    bind(Gson.class)
        .annotatedWith(BrokerGson.class)
        .toProvider(GsonProvider.class)
        .in(Singleton.class);
  }

  @Provides
  @Singleton
  @InstanceId
  UUID getInstanceId(SitePaths sitePaths) throws IOException {
    UUID instanceId = null;
    Path dataDir = sitePaths.data_dir.resolve(Configuration.PLUGIN_NAME);
    if (!dataDir.toFile().exists()) {
      dataDir.toFile().mkdirs();
    }
    String serverIdFile =
        dataDir.toAbsolutePath().toString() + "/" + Configuration.INSTANCE_ID_FILE;

    instanceId = tryToLoadSavedInstanceId(serverIdFile);

    if (instanceId == null) {
      instanceId = UUID.randomUUID();
      Files.createFile(Paths.get(serverIdFile));
      try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(serverIdFile))) {
        writer.write(instanceId.toString());
      } catch (IOException e) {
        log.warn(
            String.format(
                "Cannot write instance ID, a new one will be generated at instance restart. (%s)",
                e.getMessage()));
      }
    }
    return instanceId;
  }

  private UUID tryToLoadSavedInstanceId(String serverIdFile) {
    if (Files.exists(Paths.get(serverIdFile))) {
      try (BufferedReader br = Files.newBufferedReader(Paths.get(serverIdFile))) {
        return UUID.fromString(br.readLine());
      } catch (IOException e) {
        log.warn(
            String.format(
                "Cannot read instance ID from path '%s', deleting the old file and generating a new ID: (%s)",
                serverIdFile, e.getMessage()));
        try {
          Files.delete(Paths.get(serverIdFile));
        } catch (IOException e1) {
          log.warn(
              String.format(
                  "Cannot delete old instance ID file at path '%s' with instance ID while generating a new one: (%s)",
                  serverIdFile, e1.getMessage()));
        }
      }
    }
    return null;
  }
}
