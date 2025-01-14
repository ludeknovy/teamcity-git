package jetbrains.buildServer.buildTriggers.vcs.git.command;

import org.jetbrains.annotations.NotNull;

public interface GitFacade {

  @NotNull
  VersionCommand version();

  @NotNull
  FetchCommand fetch();

  @NotNull
  LsRemoteCommand lsRemote();

  @NotNull
  RemoteCommand remote();

  @NotNull
  PushCommand push();

  @NotNull
  UpdateRefCommand updateRef();

  @NotNull
  TagCommand tag();

  @NotNull
  GetConfigCommand getConfig();

  @NotNull
  SetConfigCommand setConfig();
}
