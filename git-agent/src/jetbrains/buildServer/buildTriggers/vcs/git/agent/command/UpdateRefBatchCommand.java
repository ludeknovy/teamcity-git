/*
 * Copyright 2000-2019 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jetbrains.buildServer.buildTriggers.vcs.git.agent.command;

import jetbrains.buildServer.vcs.VcsException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Interface for 'update-ref --stdin'
 * Requires at least git v1.8.5
 */
public interface UpdateRefBatchCommand extends BaseCommand {

  // Supported commands
  // update SP <ref> SP <newValue> [SP <oldValue>] LF
  // create SP <ref> SP <newValue> LF
  // delete SP <ref> [SP <oldValue>] LF
  // verify SP <ref> [SP <oldValue>] LF
  // option SP <opt> LF


  @NotNull
  UpdateRefBatchCommand update(@NotNull String ref, @NotNull String value, @Nullable String oldValue) throws VcsException;

  @NotNull
  UpdateRefBatchCommand create(@NotNull String ref, @NotNull String value) throws VcsException;

  @NotNull
  UpdateRefBatchCommand delete(@NotNull String ref, @Nullable String oldValue) throws VcsException;

  @NotNull
  UpdateRefBatchCommand verify(@NotNull String ref, @Nullable String oldValue) throws VcsException;

  @NotNull
  UpdateRefBatchCommand option(@NotNull String option) throws VcsException;

  void call() throws VcsException;

}
