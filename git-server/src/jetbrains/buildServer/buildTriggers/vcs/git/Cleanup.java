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

package jetbrains.buildServer.buildTriggers.vcs.git;

import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.openapi.util.Pair;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import jetbrains.buildServer.ExecResult;
import jetbrains.buildServer.SimpleCommandLineProcessRunner;
import jetbrains.buildServer.util.Dates;
import jetbrains.buildServer.util.FileUtil;
import jetbrains.buildServer.util.StringUtil;
import jetbrains.buildServer.util.TimePrinter;
import jetbrains.buildServer.vcs.VcsException;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.internal.storage.file.PackFile;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static jetbrains.buildServer.log.Loggers.CLEANUP;

public class Cleanup {

  private static final Pattern PATTERN_LOOSE_OBJECT = Pattern.compile("[0-9a-fA-F]{38}");
  private static final Semaphore ourSemaphore = new Semaphore(1);

  private final RepositoryManager myRepositoryManager;
  private final ServerPluginConfig myConfig;
  private final GcErrors myGcErrors;
  private final AtomicReference<RunGitError> myNativeGitError = new AtomicReference<>();
  @NotNull
  private volatile Consumer<Runnable> myCleanupCallWrapper = Runnable::run;

  public Cleanup(@NotNull final ServerPluginConfig config,
                 @NotNull final RepositoryManager repositoryManager,
                 @NotNull final GcErrors gcErrors) {
    myConfig = config;
    myRepositoryManager = repositoryManager;
    myGcErrors = gcErrors;
  }

  public void run() {
    if (!ourSemaphore.tryAcquire()) {
      CLEANUP.info("Skip git cleanup: another git cleanup process is running");
      return;
    }

    try {
      CLEANUP.info("Git cleanup started");
      myCleanupCallWrapper.accept(() -> {
        removeUnusedRepositories();
        cleanupMonitoringData();
        if (myConfig.isRunNativeGC()) {
          runNativeGC();
        } else if (myConfig.isRunJGitGC()) {
          runJGitGC();
        }
      });
      CLEANUP.info("Git cleanup finished");
    } finally {
      ourSemaphore.release();
    }
  }

  public void setCleanupCallWrapper(@NotNull Consumer<Runnable> cleanupCallWrapper) {
    myCleanupCallWrapper = cleanupCallWrapper;
  }

  private void removeUnusedRepositories() {
    List<File> unusedDirs = getUnusedDirs();
    CLEANUP.debug("Remove unused git repository clones started");
    for (File dir : unusedDirs) {
      CLEANUP.info("Remove unused git repository dir " + dir.getAbsolutePath());
      Lock rmLock = myRepositoryManager.getRmLock(dir).writeLock();
      rmLock.lock();
      boolean deleted;
      try {
        deleted = FileUtil.delete(dir);
      } finally {
        rmLock.unlock();
      }
      if (deleted) {
        myGcErrors.clearError(dir);
        CLEANUP.debug("Remove unused git repository dir " + dir.getAbsolutePath() + " finished");
      } else {
        CLEANUP.error("Cannot delete unused git repository dir " + dir.getAbsolutePath());
        myRepositoryManager.invalidate(dir);
      }
    }
    CLEANUP.debug("Remove unused git repository clones finished");
  }

  @NotNull
  private List<File> getUnusedDirs() {
    return myRepositoryManager.getExpiredDirs();
  }

  @NotNull
  private List<File> getAllRepositoryDirs() {
    List<File> dirs = new ArrayList<>();
    for (File d: FileUtil.getSubDirectories(myRepositoryManager.getBaseMirrorsDir())) {
      if (d.getName().endsWith(".git")) { // there can be some other directories like .gc or .old, we should ignore them
        dirs.add(d);
      }
    }
    return dirs;
  }

  @NotNull
  private List<File> getRepositoryDirCopiesCreatedByGc() {
    List<File> dirs = new ArrayList<>();
    for (File d: FileUtil.getSubDirectories(myRepositoryManager.getBaseMirrorsDir())) {
      if (d.getName().contains(".git.gc")) {
        dirs.add(d);
      }
    }
    return dirs;
  }

  private void cleanupMonitoringData() {
    CLEANUP.debug("Start cleaning git monitoring data");
    for (File repository : getAllRepositoryDirs()) {
      File monitoring = new File(repository, myConfig.getMonitoringDirName());
      File[] files = monitoring.listFiles();
      if (files != null) {
        for (File monitoringData : files) {
          if (isExpired(monitoringData)) {
            CLEANUP.debug("Remove old git monitoring data " + monitoringData.getAbsolutePath());
            FileUtil.delete(monitoringData);
          }
        }
      }
    }
    CLEANUP.debug("Finish cleaning git monitoring data");
  }

  private boolean isExpired(@NotNull File f) {
    long age = System.currentTimeMillis() - f.lastModified();
    long ageHours = age / Dates.ONE_HOUR;
    return ageHours > myConfig.getMonitoringExpirationTimeoutHours();
  }

  private void runNativeGC() {
    final long startNanos = System.nanoTime();
    final long gcTimeQuotaNanos = TimeUnit.MINUTES.toNanos(myConfig.getNativeGCQuotaMinutes());

    List<File> brokenGcCopyDirs = getRepositoryDirCopiesCreatedByGc();
    if (!brokenGcCopyDirs.isEmpty()) {
      CLEANUP.info("Found several copies of repositories directories left after the failed GC attempts: " + brokenGcCopyDirs + ", will remove all of them");
    }
    for (File brokenDir: brokenGcCopyDirs) {
      FileUtil.delete(brokenDir);
    }

    List<File> allDirs = getAllRepositoryDirs();
    myGcErrors.retainErrors(allDirs);
    if (allDirs.isEmpty()) {
      CLEANUP.debug("No repositories found");
      //reset error, no reason to show it if there is no repositories
      myNativeGitError.set(null);
      return;
    }

    if (!isNativeGitInstalled()) {
      CLEANUP.info("Cannot find native git, skip running git gc");
      return;
    }
    Long freeDiskSpace = FileUtil.getFreeSpace(myRepositoryManager.getBaseMirrorsDir());
    CLEANUP.info("Use git at path '" + myConfig.getPathToGit() + "'");
    Collections.shuffle(allDirs);
    int runGCCounter = 0;
    CLEANUP.info("Git garbage collection started");
    boolean runInPlace = myConfig.runInPlaceGc();
    for (File gitDir : allDirs) {
      String url = myRepositoryManager.getUrl(gitDir.getName());
      if (url != null) {
        CLEANUP.debug("[" + gitDir.getName() + "] repository url: '" + url + "'");
      }
      if (enoughDiskSpaceForGC(gitDir, freeDiskSpace)) {
        if (runInPlace) {
          ReentrantLock lock = myRepositoryManager.getWriteLock(gitDir);
          lock.lock();
          try {
            runNativeGC(gitDir);
          } finally {
            lock.unlock();
          }
        } else {
          runGcInCopy(gitDir);
        }
      } else {
        myGcErrors.registerError(gitDir, "Not enough disk space to run git gc");
        CLEANUP.warn("[" + gitDir.getName() + "] not enough disk space to run git gc (" + String.valueOf(freeDiskSpace) + " " + pluralize("byte", freeDiskSpace) + ")");
      }
      runGCCounter++;
      final long repositoryFinishNanos = System.nanoTime();
      if ((repositoryFinishNanos - startNanos) > gcTimeQuotaNanos) {
        final int restRepositories = allDirs.size() - runGCCounter;
        if (restRepositories > 0) {
          CLEANUP.info("Git garbage collection quota exceeded, skip " + restRepositories + " repositories");
          break;
        }
      }
    }
    final long finishNanos = System.nanoTime();
    final long deltaMillis = TimeUnit.NANOSECONDS.toMillis(finishNanos - startNanos);
    CLEANUP.info("Git garbage collection finished, it took " + TimePrinter.createMillisecondsFormatter().formatTime(deltaMillis));
  }


  private void runGcInCopy(@NotNull File originalRepo) {
    Lock rmLock = myRepositoryManager.getRmLock(originalRepo).readLock();
    rmLock.lock();
    File gcRepo;
    try {
      if (!isGcNeeded(originalRepo)) {
        CLEANUP.debug("[" + originalRepo.getName() + "] no git gc is needed");
        myGcErrors.clearError(originalRepo);
        return;
      }

      try {
        gcRepo = setupGcRepo(originalRepo);
      } catch (Exception e) {
        myGcErrors.registerError(originalRepo, "Failed to create temporary repository for garbage collection", e);
        CLEANUP.warnAndDebugDetails("Failed to create temporary repository for garbage collection, original repository: " + originalRepo.getAbsolutePath(), e);
        return;
      }

      CLEANUP.info("[" + originalRepo.getName() + "] run git gc in dedicated dir [" + gcRepo.getName() + "]");

      try {
        repack(gcRepo);
        packRefs(gcRepo);
      } catch (Exception e) {
        myGcErrors.registerError(originalRepo, "Error while running garbage collection", e);
        CLEANUP.warnAndDebugDetails("Error while running garbage collection in " + originalRepo.getAbsolutePath(), e);
        FileUtil.delete(gcRepo);
        return;
      }
    } finally {
      rmLock.unlock();
    }

    //remove alternates pointing to the original repo before swapping repositories
    FileUtil.delete(new File(gcRepo, "objects/info/alternates"));

    long swapStart = System.currentTimeMillis();
    File oldDir;
    try {
      oldDir = createTempDir(originalRepo.getParentFile(), originalRepo.getName() + ".old");
      FileUtil.delete(oldDir);
    } catch (Exception e) {
      myGcErrors.registerError(originalRepo, "Error while creating temporary directory", e);
      CLEANUP.warnAndDebugDetails("Error while creating temporary directory for " + originalRepo.getAbsolutePath(), e);
      FileUtil.delete(gcRepo);
      return;
    }

    //swap repositories with write rm lock which guarantees no one uses the original repository
    Lock rmWriteLock = myRepositoryManager.getRmLock(originalRepo).writeLock();
    long lockStart = System.currentTimeMillis();
    rmWriteLock.lock();
    long lockDuration = System.currentTimeMillis() - lockStart;
    try {
      if (!renameDir(originalRepo, oldDir, 5)) {
        myGcErrors.registerError(originalRepo, "Failed to rename " + originalRepo.getName() + " to " + oldDir.getName());
        CLEANUP.warn("Failed to rename " + originalRepo.getName() + " to " + oldDir.getName() + " after several attempts");
        return;
      }
      if (!renameDir(gcRepo, originalRepo, 5)) {
        myGcErrors.registerError(originalRepo, "Failed to rename " + gcRepo.getName() + " to " + originalRepo.getName());
        CLEANUP.warn("Failed to rename " + gcRepo.getName() + " to " + originalRepo.getName() + " after several attempts, will try restoring old repository");
        if (!oldDir.renameTo(originalRepo)) {
          CLEANUP.warn("Failed to rename " + oldDir.getName() + " to " + originalRepo.getName());
        }
        return;
      }
    } finally {
      rmWriteLock.unlock();
      FileUtil.delete(oldDir);
      FileUtil.delete(gcRepo);
    }
    long swapDuration = System.currentTimeMillis() - swapStart;
    if (swapDuration > TimeUnit.SECONDS.toMillis(5)) {
      String msg = "[" + originalRepo.getName() + "] swap with compacted repository finished in " + swapDuration + "ms";
      if (lockDuration > TimeUnit.SECONDS.toMillis(1)) {
        msg += " (lock acquired in " + lockDuration + "ms)";
      }
      CLEANUP.info(msg);
    }
    myGcErrors.clearError(originalRepo);
  }

  @SuppressWarnings({"BooleanMethodIsAlwaysInverted", "SameParameterValue"})
  private boolean renameDir(@NotNull File prevDir, @NotNull File newDir, int numAttempts) {
    try {
      for (int i=0; i<numAttempts; i++) {
        if (prevDir.renameTo(newDir)) return true;
        Thread.sleep(100);
      }
    } catch (InterruptedException e) {
      CLEANUP.warn("Could not rename directory " + prevDir.getAbsolutePath() + " to " + newDir.getAbsolutePath() + ", operation was interrupted");
    }
    return false;
  }

  private void repack(final File gcRepo) throws VcsException {
    long start = System.currentTimeMillis();
    GeneralCommandLine cmd = new GeneralCommandLine();
    cmd.setWorkingDirectory(gcRepo);
    cmd.setExePath(myConfig.getPathToGit());
    cmd.addParameter("repack");
    cmd.addParameters(myConfig.getRepackCommandArguments());
    ExecResult result = SimpleCommandLineProcessRunner.runCommand(cmd, null, new SimpleCommandLineProcessRunner.RunCommandEventsAdapter() {
      @Override
      public Integer getOutputIdleSecondsTimeout() {
        return myConfig.getRepackIdleTimeoutSeconds();
      }
      @Override
      public void onProcessFinished(@NotNull final Process ps) {
        CLEANUP.info("[" + gcRepo.getName() + "] \"" + cmd.getCommandLineString() + "\" finished in " + TimePrinter.createMillisecondsFormatter().formatTime((System.currentTimeMillis() - start)));
      }
    });
    VcsException commandError = CommandLineUtil.getCommandLineError("git repack", result);
    if (commandError != null) {
      CLEANUP.warnAndDebugDetails("Error while running 'git repack' in \"" + gcRepo.getAbsolutePath() + "\"", commandError);
      throw commandError;
    }
  }

  private void packRefs(@NotNull File gcRepo) throws VcsException {
    long start = System.currentTimeMillis();
    GeneralCommandLine cmd = new GeneralCommandLine();
    cmd.setWorkingDirectory(gcRepo);
    cmd.setExePath(myConfig.getPathToGit());
    cmd.addParameter("pack-refs");
    cmd.addParameters("--all");
    ExecResult result = SimpleCommandLineProcessRunner.runCommand(cmd, null, new SimpleCommandLineProcessRunner.RunCommandEventsAdapter() {
      @Override
      public Integer getOutputIdleSecondsTimeout() {
        return myConfig.getPackRefsIdleTimeoutSeconds();
      }
      @Override
      public void onProcessFinished(@NotNull final Process ps) {
        CLEANUP.info("[" + gcRepo.getName() + "] 'git pack-refs --all' finished in " + (System.currentTimeMillis() - start) + "ms");
      }
    });
    VcsException commandError = CommandLineUtil.getCommandLineError("git pack-refs", result);
    if (commandError != null) {
      CLEANUP.warnAndDebugDetails("Error while running 'git pack-refs' in " + gcRepo.getAbsolutePath(), commandError);
      throw commandError;
    }
  }

  private boolean isGcNeeded(@NotNull File gitDir) {
    FileRepository db = null;
    try {
      //implement logic from git gc --auto, jgit version we use doesn't have it yet
      //and native git doesn't provide a dedicated command for that
      db = (FileRepository) new RepositoryBuilder().setBare().setGitDir(gitDir).build();
      return tooManyPacks(db) || tooManyLooseObjects(db);
    } catch (IOException e) {
      CLEANUP.warnAndDebugDetails("Error while checking if garbage collection is needed in " + gitDir.getAbsolutePath(), e);
      return false;
    } finally {
      if (db != null)
        db.close();
    }
  }

  private boolean enoughDiskSpaceForGC(@NotNull File gitDir, @Nullable Long freeDiskSpace) {
    if (freeDiskSpace == null)
      return true;
    File objects = new File(gitDir, "objects");
    File pack = new File(objects, "pack");
    return FileUtil.getTotalDirectorySize(pack) < freeDiskSpace;
  }

  private boolean tooManyPacks(@NotNull FileRepository repo) {
    int limit = repo.getConfig().getInt("gc", "autopacklimit", 50);
    if (limit <= 0)
      return false;
    int packCount = 0;
    for (PackFile packFile : repo.getObjectDatabase().getPacks()) {
      if (!packFile.shouldBeKept())
        packCount++;
      if (packCount > limit)
        return true;
    }
    return false;
  }


  private boolean tooManyLooseObjects(@NotNull FileRepository repo) {
    int limit = repo.getConfig().getInt("gc", "auto", 6700);
    if (limit == 0) return false;
    if (limit == -1) limit = 6700;
    //SHA is evenly distributed, we can estimate number of loose object by counting them in a single bucket (from jgit internals)
    int bucketLimit = (limit + 255) / 256;
    File bucket = new File(repo.getObjectsDirectory(), "17");
    if (!bucket.isDirectory())
      return false;
    String[] files = bucket.list();
    if (files == null)
      return false;
    int count = 0;
    for (String fileName : files) {
      if (PATTERN_LOOSE_OBJECT.matcher(fileName).matches())
        count++;
      if (count > bucketLimit)
        return true;
    }
    return false;
  }


  @NotNull
  private File setupGcRepo(@NotNull File gitDir) throws IOException {
    File result = createTempDir(gitDir.getParentFile(), gitDir.getName() + ".gc");
    Repository repo = new RepositoryBuilder().setBare().setGitDir(result).build();
    try {
      repo.create(true);
    } finally {
      repo.close();
    }

    //setup alternates, 'git repack' in a repo with alternates creates a pack
    //in this repo without affecting the repo alternates point to
    File objectsDir = new File(result, "objects");
    File objectsInfo = new File(objectsDir, "info");
    objectsInfo.mkdirs();
    FileUtil.writeFileAndReportErrors(new File(objectsInfo, "alternates"), new File(gitDir, "objects").getCanonicalPath());

    copyIfExist(new File(gitDir, "packed-refs"), result);
    copyIfExist(new File(gitDir, "timestamp"), result);
    copyIfExist(new File(gitDir, "config"), result);
    copyDirIfExist(new File(gitDir, "refs"), result);
    copyDirIfExist(new File(gitDir, "monitoring"), result);
    return result;
  }

  private void copyIfExist(@NotNull File srcFile, @NotNull File dstDir) throws IOException {
    if (srcFile.exists())
      FileUtil.copy(srcFile, new File(dstDir, srcFile.getName()));
  }

  private void copyDirIfExist(@NotNull File srcDir, @NotNull File dstDir) throws IOException {
    if (srcDir.exists())
      FileUtil.copyDir(srcDir, new File(dstDir, srcDir.getName()));
  }

  @NotNull
  private File createTempDir(@NotNull final File parentDir, @NotNull String name) {
    File dir = new File(parentDir, name);
    if (dir.mkdir())
      return dir;

    int suffix = 0;
    while (true) {
      suffix++;
      String tmpDirName = name + suffix;
      dir = new File(parentDir, tmpDirName);
      if (dir.mkdir())
        return dir;
    }
  }

  private void runJGitGC() {
    final long startNanos = System.nanoTime();
    final long gcTimeQuotaNanos = TimeUnit.MINUTES.toNanos(myConfig.getNativeGCQuotaMinutes());
    CLEANUP.info("Git garbage collection started");
    List<File> allDirs = getAllRepositoryDirs();
    Collections.shuffle(allDirs);
    int runGCCounter = 0;
    Boolean nativeGitInstalled = null;
    boolean enableNativeGitLogged = false;
    for (File gitDir : allDirs) {
      ReentrantLock lock = myRepositoryManager.getWriteLock(gitDir);
      lock.lock();
      try {
        try {
          CLEANUP.info("Start garbage collection in " + gitDir.getAbsolutePath());
          long repositoryStartNanos = System.nanoTime();
          runJGitGC(gitDir);
          CLEANUP.info("Garbage collection finished in " + gitDir.getAbsolutePath() + ", duration: " +
                               TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - repositoryStartNanos) + "ms");
        } catch (Exception e) {
          CLEANUP.warnAndDebugDetails("Error while running garbage collection in " + gitDir.getAbsolutePath(), e);
          if ((System.nanoTime() - startNanos) < gcTimeQuotaNanos) { //if quota is not exceeded try running a native git
            if (nativeGitInstalled == null) {
              CLEANUP.info("Check if native git is installed");
              nativeGitInstalled = isNativeGitInstalled();
            }
            if (nativeGitInstalled) {
              runNativeGC(gitDir);
            } else {
              if (!enableNativeGitLogged) {
                CLEANUP.info("Cannot find a native git, please install it and provide a path to git in the 'teamcity.server.git.executable.path' internal property.");
                enableNativeGitLogged = true;
              }
            }
          }
        }
      } finally {
        lock.unlock();
      }

      runGCCounter++;
      final long repositoryFinishNanos = System.nanoTime();
      if ((repositoryFinishNanos - startNanos) > gcTimeQuotaNanos) {
        final int restRepositories = allDirs.size() - runGCCounter;
        if (restRepositories > 0) {
          CLEANUP.info("Git garbage collection quota exceeded, skip " + restRepositories + " repositories");
          break;
        }
      }
    }
    final long finishNanos = System.nanoTime();
    CLEANUP.info("Git garbage collection finished, it took " + TimeUnit.NANOSECONDS.toMillis(finishNanos - startNanos) + "ms");
  }

  private boolean isNativeGitInstalled() {
    String pathToGit = myConfig.getPathToGit();
    GeneralCommandLine cmd = new GeneralCommandLine();
    cmd.setWorkingDirectory(myRepositoryManager.getBaseMirrorsDir());
    cmd.setExePath(pathToGit);
    cmd.addParameter("version");
    ExecResult result = SimpleCommandLineProcessRunner.runCommand(cmd, null);
    VcsException commandError = CommandLineUtil.getCommandLineError("git version", result);
    if (commandError != null) {
      myNativeGitError.set(new RunGitError(pathToGit, commandError));
      CLEANUP.warnAndDebugDetails("Failed to run git", commandError);
      return false;
    } else {
      myNativeGitError.set(null);
    }
    return true;
  }

  private void runJGitGC(final File bareGitDir) throws IOException, VcsException {
    GeneralCommandLine cmd = new GeneralCommandLine();
    cmd.setWorkingDirectory(bareGitDir);
    cmd.setExePath(myConfig.getFetchProcessJavaPath());
    cmd.addParameters("-Xmx" + myConfig.getGcProcessMaxMemory(),
                      "-cp", myConfig.getFetchClasspath(),
                      GitGcProcess.class.getName(),
                      bareGitDir.getCanonicalPath());
    ExecResult result = SimpleCommandLineProcessRunner.runCommand(cmd, null, new SimpleCommandLineProcessRunner.RunCommandEventsAdapter() {
      @NotNull
      @Override
      public Integer getOutputIdleSecondsTimeout() {
        return 60 * myConfig.getNativeGCQuotaMinutes();
      }
    });
    VcsException commandError = CommandLineUtil.getCommandLineError("git gc", result, false, true);
    if (commandError != null)
      throw commandError;
  }

  private void runNativeGC(final File bareGitDir) {
    String pathToGit = myConfig.getPathToGit();
    try {
      final long start = System.currentTimeMillis();
      GeneralCommandLine cl = new GeneralCommandLine();
      cl.setWorkingDirectory(bareGitDir.getParentFile());
      cl.setExePath(pathToGit);
      cl.addParameter("--git-dir="+bareGitDir.getCanonicalPath());
      cl.addParameter("gc");
      cl.addParameter("--auto");
      cl.addParameter("--quiet");

      ExecResult result = SimpleCommandLineProcessRunner.runCommand(cl, null, new SimpleCommandLineProcessRunner.ProcessRunCallback() {
        public void onProcessStarted(@NotNull Process ps) {
          CLEANUP.info("Start 'git --git-dir=" + bareGitDir.getAbsolutePath() + " gc'");
        }
        public void onProcessFinished(@NotNull Process ps) {
          final long finish = System.currentTimeMillis();
          CLEANUP.info("Finish 'git --git-dir=" + bareGitDir.getAbsolutePath() + " gc', duration: " + (finish - start) + "ms");
        }
        public Integer getOutputIdleSecondsTimeout() {
          return 60 * myConfig.getNativeGCQuotaMinutes();
        }
        public Integer getMaxAcceptedOutputSize() {
          return null;
        }
        @Override
        public boolean terminateEntireProcessTree() {
          return true;
        }
      });

      VcsException commandError = CommandLineUtil.getCommandLineError("'git --git-dir=" + bareGitDir.getAbsolutePath() + " gc'", result);
      if (commandError != null) {
        CLEANUP.warnAndDebugDetails("Error while running 'git --git-dir=" + bareGitDir.getAbsolutePath() + " gc'", commandError);
      }
      if (result.getStderr().length() > 0) {
        CLEANUP.debug("Output produced by 'git --git-dir=" + bareGitDir.getAbsolutePath() + " gc'");
        CLEANUP.debug(result.getStderr());
      }
    } catch (Exception e) {
      myGcErrors.registerError(bareGitDir, e);
      CLEANUP.warnAndDebugDetails("Error while running 'git --git-dir=" + bareGitDir.getAbsolutePath() + " gc'", e);
    }
  }


  @Nullable
  public RunGitError getNativeGitError() {
    return myNativeGitError.get();
  }

  public static class RunGitError extends Pair<String, VcsException> {
    public RunGitError(@NotNull String gitPath, @NotNull VcsException error) {
      super(gitPath, error);
    }

    @NotNull
    public String getGitPath() {
      return first;
    }

    @NotNull
    public VcsException getError() {
      return second;
    }
  }

  @NotNull
  private String pluralize(@NotNull String base, long n) {
    //StringUtil doesn't work with longs
    if (n == 1) return base;
    return StringUtil.pluralize(base);
  }
}
