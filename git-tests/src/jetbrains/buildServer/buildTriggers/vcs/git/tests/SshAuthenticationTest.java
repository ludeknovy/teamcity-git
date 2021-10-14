/*
 * Copyright 2000-2021 JetBrains s.r.o.
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

package jetbrains.buildServer.buildTriggers.vcs.git.tests;

import com.jcraft.jsch.JSch;
import jetbrains.buildServer.BaseTestCase;
import jetbrains.buildServer.TempFiles;
import jetbrains.buildServer.buildTriggers.vcs.git.*;
import jetbrains.buildServer.buildTriggers.vcs.git.command.GitExec;
import jetbrains.buildServer.buildTriggers.vcs.git.command.impl.GitFacadeImpl;
import jetbrains.buildServer.buildTriggers.vcs.git.command.impl.GitRepoOperationsImpl;
import jetbrains.buildServer.buildTriggers.vcs.git.command.impl.StubContext;
import jetbrains.buildServer.serverSide.ServerPaths;
import jetbrains.buildServer.ssh.TeamCitySshKey;
import jetbrains.buildServer.ssh.VcsRootSshKeyManager;
import jetbrains.buildServer.util.FileUtil;
import jetbrains.buildServer.util.jsch.JSchConfigInitializer;
import jetbrains.buildServer.vcs.VcsException;
import org.apache.log4j.Level;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;
import java.util.stream.Collectors;

import static jetbrains.buildServer.buildTriggers.vcs.git.tests.GitTestUtil.dataFile;

@Test
public class SshAuthenticationTest extends BaseTestCase {
  protected TempFiles myTempFiles;

  @BeforeMethod
  public void setUp() throws Exception {
    myTempFiles = new TempFiles();
  }

  private static class GitSshContainer extends GenericContainer<GitSshContainer> {
    public GitSshContainer(final ImageFromDockerfile dockerfile) {
      super(dockerfile);
    }
  }

  // native/jgit + key rsa and others + auth type + url type + encrypted + operation + errors

  public void ssh_git_password_native() throws Exception {
    do_ssh_test(true, true, "ssh://git@%s:%s/home/git/repo.git", "PasswordAuthentication yes\nPubkeyAuthentication no", null, null,
                b -> b.withAuthMethod(AuthenticationMethod.PASSWORD).withPassword("git_user_pass")
    );
  }

  public void ssh_git_password() throws Exception {
    do_ssh_test(false, true, "ssh://git@%s:%s/home/git/repo.git", "PasswordAuthentication yes\nPubkeyAuthentication no", null, null,
                b -> b.withAuthMethod(AuthenticationMethod.PASSWORD).withPassword("git_user_pass")
    );
  }

  @Test(dataProvider = "true,false")
  public void ssh_git_password_wrong_username(boolean nativeOperationsEnabled) throws Exception {
    try {
      do_ssh_test(nativeOperationsEnabled, true, "ssh://bob@%s:%s/home/git/repo.git", "PasswordAuthentication yes\nPubkeyAuthentication no", null, null,
                  b -> b.withAuthMethod(AuthenticationMethod.PASSWORD).withPassword("git_user_pass")
      );
      fail("Exception was expected");
    } catch (Exception e) {
      final String msg = e.getMessage();
      if (msg.contains("Auth fail") || msg.contains("Permission denied")) return;
      throw e;
    }
  }

  public void ssh_git_wrong_password() throws Exception {
    try {
      do_ssh_test(false, true, "ssh://git@%s:%s/home/git/repo.git", "PasswordAuthentication yes\nPubkeyAuthentication no", null, null,
                  b -> b.withAuthMethod(AuthenticationMethod.PASSWORD).withPassword("wrong_pass")
      );
      fail("Exception was expected");
    } catch (Exception e) {
      if (e.getMessage().contains("Auth fail")) return;
      throw e;
    }
  }

  public void ssh_git_wrong_password_native() throws Exception {
    try {
      do_ssh_test(true, true, "ssh://git@%s:%s/home/git/repo.git", "PasswordAuthentication yes\nPubkeyAuthentication no", null, null,
                  b -> b.withAuthMethod(AuthenticationMethod.PASSWORD).withPassword("wrong_pass")
      );
      fail("Exception was expected");
    } catch (Exception e) {
      if (e.getMessage().contains("Permission denied")) return;
      throw e;
    }
  }

  public void ssh_git_password_disabled() throws Exception {
    try {
      do_ssh_test(false, true, "ssh://git@%s:%s/home/git/repo.git", "PasswordAuthentication no", null, null,
                  b -> b.withAuthMethod(AuthenticationMethod.PASSWORD).withPassword("git_user_pass")
      );
      fail("Exception was expected");
    } catch (Exception e) {
      if (e.getMessage().contains("Auth fail")) return;
      throw e;
    }
  }

  public void ssh_git_password_disabled_native() throws Exception {
    try {
      do_ssh_test(true, true, "ssh://git@%s:%s/home/git/repo.git", "PasswordAuthentication no", null, null,
                  b -> b.withAuthMethod(AuthenticationMethod.PASSWORD).withPassword("git_user_pass")
      );
      fail("Exception was expected");
    } catch (Exception e) {
      if (e.getMessage().contains("Permission denied")) return;
      throw e;
    }
  }

  @Test(dataProvider = "true,false")
  public void ssh_git_ecdsa_key_file(boolean nativeOperationsEnabled) throws Exception {
    final File key = dataFile("keys/id_ecdsa");
    do_ssh_test(nativeOperationsEnabled, true, "ssh://git@%s:%s/home/git/repo.git", "", null, "keys/id_ecdsa.pub",
                b -> b.withAuthMethod(AuthenticationMethod.PRIVATE_KEY_FILE).withPrivateKeyPath(key.getAbsolutePath())
    );
  }

  public void ssh_git_ecdsa_key_file_native_passphrase_provided() throws Exception {
    final File key = dataFile("keys/id_ecdsa");
    do_ssh_test(true, true, "ssh://git@%s:%s/home/git/repo.git", "", null, "keys/id_ecdsa.pub",
                b -> b.withAuthMethod(AuthenticationMethod.PRIVATE_KEY_FILE).withPrivateKeyPath(key.getAbsolutePath()).withPassphrase("12345")
    );
  }

  @Test(dataProvider = "true,false")
  public void ssh_git_rsa_key_file(boolean nativeOperationsEnabled) throws Exception {
    final File key = dataFile("keys/id_rsa");
    do_ssh_test(nativeOperationsEnabled, true, "ssh://git@%s:%s/home/git/repo.git", "", null, "keys/id_rsa.pub",
                b -> b.withAuthMethod(AuthenticationMethod.PRIVATE_KEY_FILE).withPrivateKeyPath(key.getAbsolutePath())
    );
  }

  public void ssh_git_ecdsa_encrypted_key_file() throws Exception {
    final File key = dataFile("keys/id_ecdsa_encrypted");
    do_ssh_test(false, true, "ssh://git@%s:%s/home/git/repo.git", "", null, "keys/id_ecdsa_encrypted.pub",
                b -> b.withAuthMethod(AuthenticationMethod.PRIVATE_KEY_FILE).withPrivateKeyPath(key.getAbsolutePath()).withPassphrase("12345")
    );
  }

  public void ssh_git_ecdsa_encrypted_key_file_native() throws Exception {
    final File key = dataFile("keys/id_ecdsa_encrypted");
    do_ssh_test(true, true, "ssh://git@%s:%s/home/git/repo.git", "", null, "keys/id_ecdsa_encrypted.pub",
                b -> b.withAuthMethod(AuthenticationMethod.PRIVATE_KEY_FILE).withPrivateKeyPath(key.getAbsolutePath()).withPassphrase("12345")
    );
  }

  public void ssh_git_ecdsa_decrypted_key_file_native() throws Exception {
    final File key = dataFile("keys/id_ecdsa_encrypted");
    do_ssh_test(true, false, "ssh://git@%s:%s/home/git/repo.git", "", null, "keys/id_ecdsa_encrypted.pub",
                b -> b.withAuthMethod(AuthenticationMethod.PRIVATE_KEY_FILE).withPrivateKeyPath(key.getAbsolutePath()).withPassphrase("12345")
    );
  }

  public void ssh_git_rsa_encrypted_key_file() throws Exception {
    final File key = dataFile("keys/id_rsa_encrypted");
    do_ssh_test(false, true, "ssh://git@%s:%s/home/git/repo.git", "", null, "keys/id_rsa_encrypted.pub",
                b -> b.withAuthMethod(AuthenticationMethod.PRIVATE_KEY_FILE).withPrivateKeyPath(key.getAbsolutePath()).withPassphrase("12345")
    );
  }

  public void ssh_git_rsa_encrypted_key_file_native() throws Exception {
    final File key = dataFile("keys/id_rsa_encrypted");
    do_ssh_test(true, true, "ssh://git@%s:%s/home/git/repo.git", "", null, "keys/id_rsa_encrypted.pub",
                b -> b.withAuthMethod(AuthenticationMethod.PRIVATE_KEY_FILE).withPrivateKeyPath(key.getAbsolutePath()).withPassphrase("12345")
    );
  }

  public void ssh_git_rsa_decrypted_key_file_native() throws Exception {
    final File key = dataFile("keys/id_rsa_encrypted");
    do_ssh_test(true, false, "ssh://git@%s:%s/home/git/repo.git", "", null, "keys/id_rsa_encrypted.pub",
                b -> b.withAuthMethod(AuthenticationMethod.PRIVATE_KEY_FILE).withPrivateKeyPath(key.getAbsolutePath()).withPassphrase("12345")
    );
  }

  @Test(dataProvider = "true,false")
  public void ssh_git_ecdsa_uploaded_key(boolean nativeOperationsEnabled) throws Exception {
    final File key = dataFile("keys/id_ecdsa");
    do_ssh_test(nativeOperationsEnabled, true, "ssh://git@%s:%s/home/git/repo.git", "", new TeamCitySshKey("test_key", Files.readAllBytes(key.toPath()), false), "keys/id_ecdsa.pub",
                b -> b.withAuthMethod(AuthenticationMethod.TEAMCITY_SSH_KEY).withTeamCitySshKey("test_key")
    );
  }

  @Test(dataProvider = "true,false")
  public void ssh_git_rsa_uploaded_key(boolean nativeOperationsEnabled) throws Exception {
    final File key = dataFile("keys/id_rsa");
    do_ssh_test(nativeOperationsEnabled, true, "ssh://git@%s:%s/home/git/repo.git", "", new TeamCitySshKey("test_key", Files.readAllBytes(key.toPath()), false), "keys/id_rsa.pub",
                b -> b.withAuthMethod(AuthenticationMethod.TEAMCITY_SSH_KEY).withTeamCitySshKey("test_key")
    );
  }

  @Test(dataProvider = "true,false")
  public void ssh_git_ecdsa_encrypted_uploaded_key(boolean nativeOperationsEnabled) throws Exception {
    final File key = dataFile("keys/id_ecdsa_encrypted");
    do_ssh_test(nativeOperationsEnabled, true, "ssh://git@%s:%s/home/git/repo.git", "", new TeamCitySshKey("test_key", Files.readAllBytes(key.toPath()), true), "keys/id_ecdsa_encrypted.pub",
                b -> b.withAuthMethod(AuthenticationMethod.TEAMCITY_SSH_KEY).withTeamCitySshKey("test_key").withPassphrase("12345")
    );
  }

  @Test(dataProvider = "true,false")
  public void ssh_git_rsa_encrypted_uploaded_key(boolean nativeOperationsEnabled) throws Exception {
    final File key = dataFile("keys/id_rsa_encrypted");
    do_ssh_test(nativeOperationsEnabled, true, "ssh://git@%s:%s/home/git/repo.git", "", new TeamCitySshKey("test_key", Files.readAllBytes(key.toPath()), true), "keys/id_rsa_encrypted.pub",
                b -> b.withAuthMethod(AuthenticationMethod.TEAMCITY_SSH_KEY).withTeamCitySshKey("test_key").withPassphrase("12345")
    );
  }

  public void ssh_git_ecdsa_native_decrypted_uploaded_key() throws Exception {
    final File key = dataFile("keys/id_ecdsa_encrypted");
    do_ssh_test(true, false, "ssh://git@%s:%s/home/git/repo.git", "", new TeamCitySshKey("test_key", Files.readAllBytes(key.toPath()), true), "keys/id_ecdsa_encrypted.pub",
                b -> b.withAuthMethod(AuthenticationMethod.TEAMCITY_SSH_KEY).withTeamCitySshKey("test_key").withPassphrase("12345")
    );
  }

  public void ssh_git_rsa_native_decrypted_uploaded_key() throws Exception {
    final File key = dataFile("keys/id_rsa_encrypted");
    do_ssh_test(true, false, "ssh://git@%s:%s/home/git/repo.git", "", new TeamCitySshKey("test_key", Files.readAllBytes(key.toPath()), true), "keys/id_rsa_encrypted.pub",
                b -> b.withAuthMethod(AuthenticationMethod.TEAMCITY_SSH_KEY).withTeamCitySshKey("test_key").withPassphrase("12345")
    );
  }

  private void do_ssh_test(boolean nativeOperationsEnabled, boolean useSshAskPass, @NotNull String urlFormat, @NotNull String sshdConfig, @Nullable TeamCitySshKey tcKey, @Nullable String publicKey, @NotNull VcsRootConfigurator builder) throws Exception {
    if (!nativeOperationsEnabled) {
      JSchConfigInitializer.initJSchConfig(JSch.class);
    }

    final File pub_key = publicKey == null ? null : dataFile(publicKey);
    ssh_test(pub_key, sshdConfig, container -> JSchLoggers.evaluateWithLoggingLevel(Level.DEBUG, () -> {
      setInternalProperty("teamcity.git.nativeOperationsEnabled", String.valueOf(nativeOperationsEnabled));
      setInternalProperty("teamcity.git.useSshAskPas", String.valueOf(useSshAskPass));
      setInternalProperty("teamcity.git.debugNativeGit", "true");

      final ServerPaths serverPaths = new ServerPaths(myTempFiles.createTempDir().getAbsolutePath());
      final ServerPluginConfig config = new PluginConfigBuilder(serverPaths).build();
      final VcsRootSshKeyManager keyManager = r -> tcKey;
      final TransportFactoryImpl transportFactory = new TransportFactoryImpl(config, keyManager);
      final GitRepoOperationsImpl repoOperations = new GitRepoOperationsImpl(config, transportFactory, keyManager,
                                                                             new FetchCommandImpl(config, transportFactory,
                                                                                                  new FetcherProperties(config),
                                                                                                  keyManager));
      final MirrorManagerImpl mirrorManager = new MirrorManagerImpl(config, new HashCalculatorImpl(), new RemoteRepositoryUrlInvestigatorImpl());
      final RepositoryManagerImpl repositoryManager = new RepositoryManagerImpl(config, mirrorManager);
      final String repoUrl = String.format(urlFormat, container.getContainerIpAddress(), container.getMappedPort(22));

      final GitVcsRoot gitRoot = new GitVcsRoot(mirrorManager, builder.config(VcsRootBuilder.vcsRoot().withFetchUrl(repoUrl).withIgnoreKnownHosts(true)).build(), new URIishHelperImpl());

      // here we test some git operations
      final URIish fetchUrl = new URIish(repoUrl);
      final Repository db = repositoryManager.openRepository(fetchUrl);
      final Map<String, Ref> refs =
        repoOperations.lsRemoteCommand(repoUrl).lsRemote(db, gitRoot);
      assertContains(refs.keySet(), "refs/pull/1");

      final StringBuilder progress = new StringBuilder();
      repoOperations.fetchCommand(repoUrl).fetch(db, fetchUrl, refs.keySet().stream()
                                                   .map(r -> new RefSpec().setSourceDestination(r, r).setForceUpdate(true)).collect(Collectors.toList()),
                                                 new FetchSettings(gitRoot.getAuthSettings(), new GitProgress() {
                                                   @Override
                                                   public void reportProgress(@NotNull final String p) {
                                                     progress.append(p).append("\n");
                                                   }

                                                   @Override
                                                   public void reportProgress(final float p, @NotNull final String stage) {
                                                     if (p < 0) {
                                                       progress.append(stage).append("\n");
                                                     } else {
                                                       int percents = (int) Math.floor(p * 100);
                                                       progress.append(stage).append(" ").append(percents).append("%");
                                                     }
                                                   }
                                                 }));
      if (nativeOperationsEnabled) {
        assertContains(progress.toString(), "* [new ref]         refs/pull/1               -> refs/pull/1");
      } else {
        assertContains(progress.toString(), "update ref remote name: refs/pull/1, local name: refs/pull/1, old object id: 0000000000000000000000000000000000000000, new object id: b896070465af79121c9a4eb5300ecff29453c164, result: NEW");
      }

      final GitVcsSupport vcsSupport =
        GitSupportBuilder.gitSupport().withServerPaths(serverPaths).withPluginConfig(config).withTransportFactory(transportFactory).build();
      repoOperations.tagCommand(vcsSupport, repoUrl).tag(vcsSupport.createContext(gitRoot.getOriginalRoot(),"tag"), "test_tag", "b896070465af79121c9a4eb5300ecff29453c164");

      assertContains(repoOperations.lsRemoteCommand(repoUrl).lsRemote(db, gitRoot).keySet(), "refs/tags/test_tag");

      return null;
    }));
  }

  private void ssh_test(@Nullable File pub_key, @NotNull String sshdConfig, @NotNull ContainerTest test) throws Exception {
    final GitSshContainer gitServer = new GitSshContainer(
      new ImageFromDockerfile()
        .withDockerfileFromBuilder(builder ->
                                     builder
                                       .from("ubuntu:latest")
                                       .run("apt-get -y update")
                                       .run("apt-get -y install openssh-server")
                                       .run("mkdir /var/run/sshd")
                                       .run("ssh-keygen -A")
                                       .run("apt-get -y install git")
                                       .run("apt-get clean && rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/")
                                       .run("adduser git")
                                       .cmd("/bin/bash", "-c", "set -e -x -u\n" +
//                                                               "pwd && whoami\n"+
                                                               "echo -e \"git_user_pass\ngit_user_pass\" | passwd git\n" +
                                                               "GIT_HOME=/home/git\n" +
                                                               "mkdir -p $GIT_HOME && echo a > $GIT_HOME/marker.txt\n" +
                                                               "mkdir $GIT_HOME/.ssh\n" +
                                                               "touch $GIT_HOME/.ssh/authorized_keys\n" +
//                                                               "ls -lah /git-server/keys\n" +
                                                               (pub_key == null ? "" :
                                                               "cat /git-server/keys/" + pub_key.getName() + " >> $GIT_HOME/.ssh/authorized_keys\n") +
                                                               "cat $GIT_HOME/.ssh/authorized_keys\n" +
                                                               "cp -r /git-server/repos/repo.git $GIT_HOME/repo.git\n" +
//                                                               "ls -lah $GIT_HOME/repo.git\n" +
                                                               "chown git:git -R $GIT_HOME && chmod 700 $GIT_HOME/.ssh  && chmod 600 $GIT_HOME/.ssh/authorized_keys\n" +
                                                               "echo \"" + sshdConfig + "\" >> /etc/ssh/sshd_config\n" +
                                                               "/usr/sbin/sshd -D")
                                       .build()))
      .withExposedPorts(22)
      .withFileSystemBind(dataFile("repo.git").getAbsolutePath(), "/git-server/repos/repo.git", BindMode.READ_ONLY)
      .withLogConsumer(l -> System.out.println("DOCKER: " + l.getUtf8String()));

    if (pub_key != null) {
      gitServer.withFileSystemBind(pub_key.getAbsolutePath(), "/git-server/keys/" + pub_key.getName(), BindMode.READ_ONLY);
    }

    gitServer.start();
    try {
      test.run(gitServer);
    } finally {
      gitServer.stop();
    }
  }

  private interface ContainerTest {
    void run(@NotNull GenericContainer container) throws Exception;
  }

  private interface VcsRootConfigurator {
    @NotNull VcsRootBuilder config(@NotNull VcsRootBuilder b);
  }
}