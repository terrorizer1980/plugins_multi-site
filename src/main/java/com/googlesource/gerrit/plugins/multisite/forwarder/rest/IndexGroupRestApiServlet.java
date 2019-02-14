// Copyright (C) 2017 The Android Open Source Project
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

package com.googlesource.gerrit.plugins.multisite.forwarder.rest;

import com.google.gerrit.reviewdb.client.AccountGroup;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.multisite.forwarder.ForwardedIndexGroupHandler;
import com.googlesource.gerrit.plugins.multisite.forwarder.events.GroupIndexEvent;
import java.io.IOException;
import java.io.Reader;

@Singleton
class IndexGroupRestApiServlet
    extends AbstractIndexRestApiServlet<AccountGroup.UUID, GroupIndexEvent> {
  private static final long serialVersionUID = -1L;

  @Inject
  IndexGroupRestApiServlet(ForwardedIndexGroupHandler handler) {
    super(handler, IndexName.GROUP);
  }

  @Override
  AccountGroup.UUID parse(String id) {
    return new AccountGroup.UUID(id);
  }

  @Override
  protected GroupIndexEvent fromJson(Reader reader) throws IOException {
    return gson.fromJson(reader, GroupIndexEvent.class);
  }
}
