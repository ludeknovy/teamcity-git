package jetbrains.buildServer.buildTriggers.vcs.git.command.impl;

import com.intellij.openapi.diagnostic.Logger;
import java.io.File;
import java.util.Collections;
import java.util.Map;
import jetbrains.buildServer.buildTriggers.vcs.git.*;
import jetbrains.buildServer.buildTriggers.vcs.git.command.GitExec;
import jetbrains.buildServer.buildTriggers.vcs.git.command.NativeGitCommands;
import jetbrains.buildServer.log.Loggers;
import jetbrains.buildServer.serverSide.IOGuard;
import jetbrains.buildServer.serverSide.TeamCityProperties;
import jetbrains.buildServer.ssh.VcsRootSshKeyManager;
import jetbrains.buildServer.vcs.CommitResult;
import jetbrains.buildServer.vcs.CommitSettings;
import jetbrains.buildServer.vcs.VcsException;
import org.eclipse.jgit.errors.NotSupportedException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.FetchConnection;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.Transport;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static jetbrains.buildServer.buildTriggers.vcs.git.GitServerUtil.friendlyNotSupportedException;
import static jetbrains.buildServer.buildTriggers.vcs.git.GitServerUtil.friendlyTransportException;

public class GitRepoOperationsImpl implements GitRepoOperations {
  private static final Logger PERFORMANCE_LOG = Logger.getInstance(GitVcsSupport.class.getName() + ".Performance");
  private static final String GIT_NATIVE_OPERATIONS_ENABLED = "teamcity.git.nativeOperationsEnabled";

  private final TransportFactory myTransportFactory;
  private final VcsRootSshKeyManager mySshKeyManager;
  private final ServerPluginConfig myConfig;
  private final FetchCommand myJGitFetchCommand;
  private GitExec myGitExec;

  public GitRepoOperationsImpl(@NotNull ServerPluginConfig config,
                               @NotNull TransportFactory transportFactory,
                               @NotNull VcsRootSshKeyManager sshKeyManager,
                               @NotNull FetchCommand jGitFetchCommand) {
    myConfig = config;
    myTransportFactory = transportFactory;
    mySshKeyManager = sshKeyManager;
    myJGitFetchCommand = jGitFetchCommand;
  }

  @NotNull
  @Override
  public FetchCommand fetchCommand(@NotNull String repoUrl) {
    if (isNativeGitOperationsEnabledAndSupported(repoUrl)) {
      return new NativeGitCommands(myConfig, this::detectGit, mySshKeyManager);
    }
    return myJGitFetchCommand;
  }

  public boolean isNativeGitOperationsEnabled(@NotNull String repoUrl) {
    if (TeamCityProperties.getBoolean(GIT_NATIVE_OPERATIONS_ENABLED)) return true;

    for (Map.Entry<String, String> e : TeamCityProperties.getPropertiesWithPrefix(GIT_NATIVE_OPERATIONS_ENABLED).entrySet()) {
      final String url = e.getKey().substring(GIT_NATIVE_OPERATIONS_ENABLED.length() + 1);
      if (repoUrl.startsWith(url)) {
        return Boolean.parseBoolean(e.getValue());
      }
    }
    return false;
  }

  public boolean isNativeGitOperationsEnabled() {
    return !TeamCityProperties.getPropertiesWithPrefix(GIT_NATIVE_OPERATIONS_ENABLED).isEmpty();
  }

  @Override
  public boolean isNativeGitOperationsSupported() {
    final GitExec gitExec = gitExec();
    return gitExec != null && GitVersion.fetchSupportsStdin(gitExec.getVersion());
  }

  private boolean isNativeGitOperationsEnabledAndSupported(@NotNull String repoUrl) {
    return isNativeGitOperationsEnabled(repoUrl) && isNativeGitOperationsSupported();
  }

  @NotNull
  @Override
  public LsRemoteCommand lsRemoteCommand(@NotNull String repoUrl) {
    if (isNativeGitOperationsEnabledAndSupported(repoUrl)) {
      return new NativeGitCommands(myConfig, this::detectGit, mySshKeyManager);
    }
    return this::getRemoteRefsJGit;
  }

  @NotNull
  private Map<String, Ref> getRemoteRefsJGit(@NotNull Repository db, @NotNull GitVcsRoot gitRoot) throws VcsException {
    try {
      return IOGuard.allowNetworkCall(() -> {
        try {
          return Retry.retry(new Retry.Retryable<Map<String, Ref>>() {
            @Override
            public boolean requiresRetry(@NotNull final Exception e) {
              return GitServerUtil.isRecoverable(e);
            }

            @Nullable
            @Override
            public Map<String, Ref> call() throws Exception {
              final long start = System.currentTimeMillis();
              try (
                final Transport transport = myTransportFactory.createTransport(db, gitRoot.getRepositoryFetchURL().get(), gitRoot.getAuthSettings(), myConfig.getRepositoryStateTimeoutSeconds());
                final FetchConnection connection = transport.openFetch()) {
                return connection.getRefsMap();
              } catch (NotSupportedException nse) {
                throw friendlyNotSupportedException(gitRoot, nse);
              } catch (WrongPassphraseException e) {
                throw new VcsException(e.getMessage(), e);
              } finally {
                final long finish = System.currentTimeMillis();
                PERFORMANCE_LOG.debug("[getRemoteRefs] repository: " + LogUtil.describe(gitRoot) + ", took " + (finish - start) + "ms");
              }
            }

            @NotNull
            @Override
            public Logger getLogger() {
              return Loggers.VCS;
            }
          }, myConfig.getConnectionRetryIntervalMillis(), myConfig.getConnectionRetryAttempts());
        } catch (TransportException t) {
          throw friendlyTransportException(t, gitRoot);
        }
      });
    } catch (Exception e) {
      if (e instanceof VcsException) {
        throw (VcsException) e;
      }
      else throw new VcsException(e);
    }
  }

  @NotNull
  private GitExec detectGit() throws VcsException {
    final String gitPath = myConfig.getPathToGit();
    if (gitPath == null) {
      throw new IllegalArgumentException("No path to git provided: please specify path to git executable using \"teamcity.server.git.executable.path\" server startup property");
    }
    if (myGitExec == null || !gitPath.equals(myGitExec.getPath())) {
      GitVersion gitVersion;
      try {
        gitVersion = new GitFacadeImpl(new File("."), new StubContext(gitPath)).version().call();
      } catch (VcsException e) {
        throw new VcsException("Unable to run git at path " + gitPath + ": please specify correct path to git executable using \"teamcity.server.git.executable.path\" server startup property", e);
      }
      if (gitVersion.isSupported()) {
        myGitExec = new GitExec(gitPath, gitVersion, null);
      } else {
        throw new VcsException("TeamCity supports git version " + GitVersion.DEPRECATED + " or higher, found git ("+ gitPath +") has version " + gitVersion + ": please upgrade git");
      }
    }
    return myGitExec;
  }

  @Nullable
  @Override
  public GitExec gitExec() {
    try {
      return detectGit();
    } catch (VcsException ignored) {
    }
    return null;
  }

  @NotNull
  @Override
  public PushCommand pushCommand(@NotNull String repoUrl) {
    if (isNativeGitOperationsEnabledAndSupported(repoUrl)) {
      return new NativeGitCommands(myConfig, this::detectGit, mySshKeyManager);
    }
    return this::pushJGit;
  }

  @NotNull
  private CommitResult pushJGit(@NotNull Repository db, @NotNull GitVcsRoot gitRoot,
                                @NotNull String commit, @NotNull String lastCommit,
                                @NotNull CommitSettings settings) throws VcsException {
    try (Transport tn = myTransportFactory.createTransport(db, gitRoot.getRepositoryPushURL().get(), gitRoot.getAuthSettings(), myConfig.getPushTimeoutSeconds())) {
      final ObjectId commitId = ObjectId.fromString(commit);
      final ObjectId lastCommitId = ObjectId.fromString(lastCommit);

      final RemoteRefUpdate ru = new RemoteRefUpdate(db, null, commitId, GitUtils.expandRef(gitRoot.getRef()), false, null, lastCommitId);
      tn.push(NullProgressMonitor.INSTANCE, Collections.singletonList(ru));
      switch (ru.getStatus()) {
        case UP_TO_DATE:
        case OK:
          Loggers.VCS.info("Change '" + settings.getDescription() + "' was successfully committed");
          return CommitResult.createSuccessResult(commitId.name());
        default: {
          StringBuilder error = new StringBuilder();
          error.append("Push failed, status: ").append(ru.getStatus());
          if (ru.getMessage() != null)
            error.append(", message: ").append(ru.getMessage());
          throw new VcsException(error.toString());
        }
      }
    } catch (VcsException e) {
      throw e;
    } catch (Exception e) {
      throw new VcsException("Error while pushing a commit, root " + gitRoot + ", revision " + commit + ", destination " + GitUtils.expandRef(gitRoot.getRef()), e);
    }
  }
}