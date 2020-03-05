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

package com.googlesource.gerrit.plugins.multisite.validation;

import com.gerritforge.gerrit.globalrefdb.GlobalRefDbLockException;
import com.google.common.base.Preconditions;
import com.google.common.flogger.FluentLogger;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.multisite.SharedRefDatabaseWrapper;
import com.googlesource.gerrit.plugins.replication.ReplicationPushFilter;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class MultisiteReplicationPushFilter implements ReplicationPushFilter {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();
  private static final String REF_META_SUFFIX = "/meta";
  public static final int MIN_WAIT_BEFORE_RELOAD_LOCAL_VERSION_MS = 1000;
  public static final int RANDOM_WAIT_BEFORE_RELOAD_LOCAL_VERSION_MS = 1000;

  static final String REPLICATION_LOG_NAME = "replication_log";
  static final Logger repLog = LoggerFactory.getLogger(REPLICATION_LOG_NAME);

  private final SharedRefDatabaseWrapper sharedRefDb;
  private final GitRepositoryManager gitRepositoryManager;

  @Inject
  public MultisiteReplicationPushFilter(
      SharedRefDatabaseWrapper sharedRefDb, GitRepositoryManager gitRepositoryManager) {
    this.sharedRefDb = sharedRefDb;
    this.gitRepositoryManager = gitRepositoryManager;
  }

  @Override
  public List<RemoteRefUpdate> filter(String projectName, List<RemoteRefUpdate> remoteUpdatesList) {
    Set<String> outdatedChanges = new HashSet<>();

    try (Repository repository =
        gitRepositoryManager.openRepository(Project.nameKey(projectName))) {
      List<RemoteRefUpdate> filteredRefUpdates =
          remoteUpdatesList.stream()
              .filter(
                  refUpdate -> {
                    boolean refUpToDate = isUpToDateWithRetry(projectName, repository, refUpdate);
                    if (!refUpToDate) {
                      repLog.warn(
                          "{} is not up-to-date with the shared-refdb and thus will NOT BE replicated",
                          refUpdate);
                      if (refUpdate.getSrcRef().endsWith(REF_META_SUFFIX)) {
                        outdatedChanges.add(getRootChangeRefPrefix(refUpdate.getSrcRef()));
                      }
                    }
                    return refUpToDate;
                  })
              .collect(Collectors.toList());

      return filteredRefUpdates.stream()
          .filter(
              refUpdate -> {
                if (outdatedChanges.contains(changePrefix(refUpdate.getSrcRef()))) {
                  repLog.warn(
                      "{} belongs to an outdated /meta ref and thus will NOT BE replicated",
                      refUpdate);
                  return false;
                }
                return true;
              })
          .collect(Collectors.toList());

    } catch (IOException ioe) {
      String message = String.format("Error while opening project: '%s'", projectName);
      repLog.error(message);
      logger.atSevere().withCause(ioe).log(message);
      return Collections.emptyList();
    }
  }

  private boolean isUpToDateWithRetry(
      String projectName, Repository repository, RemoteRefUpdate refUpdate) {
    String ref = refUpdate.getSrcRef();
    try {
      if (sharedRefDb.isUpToDate(
          Project.nameKey(projectName),
          new ObjectIdRef.Unpeeled(Ref.Storage.NETWORK, ref, refUpdate.getNewObjectId()))) {
        return true;
      }

      randomSleepForMitigatingConditionWhereLocalRefHaveJustBeenChanged(
          projectName, refUpdate, ref);

      return sharedRefDb.isUpToDate(
          Project.nameKey(projectName),
          new ObjectIdRef.Unpeeled(Ref.Storage.NETWORK, ref, getNotNullExactRef(repository, ref)));
    } catch (GlobalRefDbLockException gle) {
      String message =
          String.format("%s is locked on shared-refdb and thus will NOT BE replicated", ref);
      repLog.error(message);
      logger.atSevere().withCause(gle).log(message);
      return false;
    } catch (IOException ioe) {
      String message =
          String.format("Error while extracting ref '%s' for project '%s'", ref, projectName);
      repLog.error(message);
      logger.atSevere().withCause(ioe).log(message);
      return false;
    }
  }

  private void randomSleepForMitigatingConditionWhereLocalRefHaveJustBeenChanged(
      String projectName, RemoteRefUpdate refUpdate, String ref) {
    int randomSleepTimeMsec =
        MIN_WAIT_BEFORE_RELOAD_LOCAL_VERSION_MS
            + new Random().nextInt(RANDOM_WAIT_BEFORE_RELOAD_LOCAL_VERSION_MS);
    repLog.debug(
        String.format(
            "'%s' is not up-to-date for project '%s' [local='%s']. Reload local ref in '%d ms' and re-check",
            ref, projectName, refUpdate.getNewObjectId(), randomSleepTimeMsec));
    try {
      Thread.sleep(randomSleepTimeMsec);
    } catch (InterruptedException ie) {
      String message =
          String.format("Error while waiting for next check for '%s', ref '%s'", projectName, ref);
      repLog.error(message);
      logger.atWarning().withCause(ie).log(message);
    }
  }

  private String changePrefix(String changeRef) {
    if (changeRef == null || !changeRef.startsWith("refs/changes")) {
      return changeRef;
    }
    if (changeRef.endsWith(REF_META_SUFFIX)) {
      return getRootChangeRefPrefix(changeRef);
    }

    // changeRef has the form '/refs/changes/NN/NNNN/P'
    return changeRef.substring(0, changeRef.lastIndexOf('/'));
  }

  private String getRootChangeRefPrefix(String changeMetaRef) {
    if (changeMetaRef.endsWith(REF_META_SUFFIX)) {
      return changeMetaRef.substring(0, changeMetaRef.length() - REF_META_SUFFIX.length());
    }

    return changeMetaRef;
  }

  private ObjectId getNotNullExactRef(Repository repository, String refName) throws IOException {
    Ref ref = repository.exactRef(refName);
    Preconditions.checkNotNull(ref);
    return ref.getObjectId();
  }
}
