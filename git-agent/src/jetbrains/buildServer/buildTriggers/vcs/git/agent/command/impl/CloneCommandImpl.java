/*
 * Copyright 2000-2018 JetBrains s.r.o.
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

package jetbrains.buildServer.buildTriggers.vcs.git.agent.command.impl;

import jetbrains.buildServer.buildTriggers.vcs.git.agent.command.CloneCommand;
import jetbrains.buildServer.buildTriggers.vcs.git.command.GitCommandLine;
import jetbrains.buildServer.buildTriggers.vcs.git.command.impl.BaseCommandImpl;
import jetbrains.buildServer.buildTriggers.vcs.git.command.impl.CommandUtil;
import jetbrains.buildServer.util.StringUtil;
import jetbrains.buildServer.vcs.VcsException;
import org.jetbrains.annotations.NotNull;

public class CloneCommandImpl extends BaseCommandImpl implements CloneCommand {

  private boolean myMirror = false;
  private String myRepo;
  private String myFolder;

  public CloneCommandImpl(@NotNull GitCommandLine cmd) {
    super(cmd);
  }


  @NotNull
  public CloneCommand setMirror(boolean mirror) {
    myMirror = mirror;
    return this;
  }

  @NotNull
  @Override
  public CloneCommand setRepo(@NotNull String repoUrl) {
    myRepo = repoUrl;
    return this;
  }

  @NotNull
  @Override
  public CloneCommand setFolder(@NotNull String folder) {
    myFolder = folder;
    return this;
  }

  public void call() throws VcsException {
    GitCommandLine cmd = getCmd();
    cmd.addParameter("clone");
    if (myMirror) {
      cmd.addParameter("--mirror");
    }
    cmd.addParameter(myRepo);
    if (StringUtil.isNotEmpty(myFolder)) {
      cmd.addParameter(myFolder);
    }
    CommandUtil.runCommand(cmd.stdErrExpected(false));
  }
}