/*
 * Copyright 2000-2014 JetBrains s.r.o.
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

package jetbrains.buildServer.buildTriggers.vcs.git.submodules;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.buildTriggers.vcs.git.*;
import jetbrains.buildServer.vcs.VcsException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.BlobBasedConfig;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.jetbrains.annotations.NotNull;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Arrays;

/**
 * The resolver for submodules
 */
public class SubmoduleResolverImpl implements SubmoduleResolver {

  private static Logger LOG = Logger.getInstance(SubmoduleResolverImpl.class.getName());
  /**
   * Path from the root of the first repository.
   * For root repository = "".
   * For submodule repository = path of submodule.
   * For sub-submodules = path of submodule/path of sub-submodule in submodule repository.
   */
  protected final String myPathFromRoot;
  protected final OperationContext myContext;

  private final RevCommit myCommit;
  private final Repository myDb;
  protected final GitVcsSupport myGitSupport;
  protected final CommitLoader myCommitLoader;
  private SubmodulesConfig myConfig;

  public SubmoduleResolverImpl(@NotNull GitVcsSupport gitSupport,
                               @NotNull OperationContext context,
                               @NotNull CommitLoader commitLoader,
                               @NotNull Repository db,
                               @NotNull RevCommit commit,
                               @NotNull String pathFromRoot) {
    myGitSupport = gitSupport;
    myCommitLoader = commitLoader;
    myDb = db;
    myCommit = commit;
    myContext = context;
    myPathFromRoot = pathFromRoot;
  }

  /**
   * Resolve the commit for submodule
   *
   * @param path   the within repository path
   * @param commit the commit identifier
   * @return the the resoled commit in other repository
   * @throws IOException if there is an IO problem during resolving repository or mapping commit
   * @throws VcsAuthenticationException if there are authentication problems
   * @throws URISyntaxException if there are errors in submodule repository URI
   */
  public RevCommit getSubmoduleCommit(String path, ObjectId commit) throws IOException, VcsException, URISyntaxException {
    ensureConfigLoaded();
    String mainRepositoryUrl = myDb.getConfig().getString("teamcity", null, "remote");
    if (myConfig == null)
      throw new MissingSubmoduleConfigException(mainRepositoryUrl, myCommit.name(), path);

    final Submodule submodule = myConfig.findSubmodule(path);
    if (submodule == null)
      throw new MissingSubmoduleEntryException(mainRepositoryUrl, myCommit.name(), path);

    Repository r = resolveRepository(submodule.getUrl());
    if (!isCommitExist(r, commit))
      fetch(r, path, submodule.getUrl());
    try {
      return myCommitLoader.getCommit(r, commit);
    } catch (MissingObjectException e) {
      throw new MissingSubmoduleCommitException(mainRepositoryUrl, myCommit.name(), path, submodule.getUrl(), commit.name());
    }
  }

  private boolean isCommitExist(final Repository r, final ObjectId commit) {
    RevWalk walk = new RevWalk(r);
    try {
      walk.parseCommit(commit);
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  public URIish resolveSubmoduleUrl(@NotNull String url) throws URISyntaxException {
    String uri = isRelative(url) ? resolveRelativeUrl(url) : url;
    return new URIish(uri);
  }

  private boolean isRelative(@NotNull String url) {
    return url.startsWith(".");
  }

  private String resolveRelativeUrl(@NotNull String relativeUrl) throws URISyntaxException {
    String baseUrl = getRepository().getConfig().getString("teamcity", null, "remote");
    URIish u = new URIish(baseUrl);
    String newPath = u.getPath();
    if (newPath.length() == 0) {
      newPath = relativeUrl;
    } else {
      newPath = GitUtils.normalizePath(newPath + '/' + relativeUrl);
    }
    return u.setPath(newPath).toPrivateString();
  }

  /**
   * Check if the specified directory is a submodule prefix
   *
   * @param path the path to check
   * @return true if the path contains submodules
   */
  public boolean containsSubmodule(String path) {
    ensureConfigLoaded();
    return myConfig != null && myConfig.isSubmodulePrefix(path);
  }

  /**
   * @return the current repository
   */
  public Repository getRepository() {
    return myDb;
  }

  /**
   * Get submodule url by it's path in current repository
   *
   * @param submodulePath path of submodule in current repository
   * @return submodule repository url or null if no submodules is registered for specified path
   */
  public String getSubmoduleUrl(String submodulePath) {
    ensureConfigLoaded();
    if (myConfig != null) {
      Submodule submodule = myConfig.findSubmodule(submodulePath);
      return submodule != null ? submodule.getUrl() : null;
    } else {
      return null;
    }
  }

  /**
   * Ensure that submodule configuration has been loaded.
   */
  private void ensureConfigLoaded() {
    if (myConfig == null) {
      try {
        myConfig = new SubmodulesConfig(myDb.getConfig(), new BlobBasedConfig(null, myDb, myCommit, ".gitmodules"));
      } catch (FileNotFoundException e) {
        // do nothing
      } catch (Exception e) {
        LOG.error("Unable to load or parse submodule configuration at: " + myCommit.getId().name(), e);
      }
    }
  }

  public Repository resolveRepository(@NotNull String submoduleUrl) throws VcsException, URISyntaxException {
    LOG.debug("Resolve repository for URL: " + submoduleUrl);
    final URIish uri = resolveSubmoduleUrl(submoduleUrl);
    Repository r = myContext.getRepositoryFor(uri);
    LOG.debug("Repository dir for submodule " + submoduleUrl + " is " + r.getDirectory().getAbsolutePath());
    return r;
  }

  public void fetch(Repository r, String submodulePath, String submoduleUrl) throws VcsException, URISyntaxException, IOException {
    if (LOG.isDebugEnabled())
      LOG.debug("Fetching submodule " + submoduleUrl + " used at " + submodulePath + " for " + myContext.getGitRoot().debugInfo());
    URIish uri = resolveSubmoduleUrl(submoduleUrl);
    myContext.fetchSubmodule(r, uri, Arrays.asList(new RefSpec("+refs/heads/*:refs/heads/*"), new RefSpec("+refs/tags/*:refs/tags/*")), myContext.getGitRoot().getAuthSettings());
  }

  public SubmoduleResolverImpl getSubResolver(RevCommit commit, String path) {
    Repository db = null;
    try {
      db = resolveRepository(getSubmoduleUrl(path));
    } catch (Exception e) {
      //exception means path does not contain submodule, use current repository
      db = getRepository();
    }
    return new SubmoduleResolverImpl(myGitSupport, myContext, myCommitLoader, db, commit, fullPath(path));
  }

  /**
   * Get full path using from local path
   *
   * @param path the path to examine
   * @return the full including the base path
   */
  private String fullPath(String path) {
    return myPathFromRoot.length() == 0 ? path : myPathFromRoot + "/" + path;
  }
}
