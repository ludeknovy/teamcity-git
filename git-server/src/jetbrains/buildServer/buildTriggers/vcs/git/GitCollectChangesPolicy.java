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

import com.intellij.openapi.diagnostic.Logger;
import java.io.IOException;
import java.util.*;
import jetbrains.buildServer.buildTriggers.vcs.git.submodules.SubmoduleException;
import jetbrains.buildServer.util.Disposable;
import jetbrains.buildServer.util.NamedDaemonThreadFactory;
import jetbrains.buildServer.vcs.*;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class GitCollectChangesPolicy implements CollectChangesBetweenRepositories, RevisionMatchedByCheckoutRulesCalculator {

  private static final Logger LOG = Logger.getInstance(GitCollectChangesPolicy.class.getName());

  private final GitVcsSupport myVcs;
  private final VcsOperationProgressProvider myProgressProvider;
  private final ServerPluginConfig myConfig;
  private final RepositoryManager myRepositoryManager;

  public GitCollectChangesPolicy(@NotNull GitVcsSupport vcs,
                                 @NotNull VcsOperationProgressProvider progressProvider,
                                 @NotNull ServerPluginConfig config,
                                 @NotNull RepositoryManager repositoryManager) {
    myVcs = vcs;
    myProgressProvider = progressProvider;
    myConfig = config;
    myRepositoryManager = repositoryManager;
  }


  @NotNull
  public List<ModificationData> collectChanges(@NotNull VcsRoot fromRoot,
                                               @NotNull RepositoryStateData fromState,
                                               @NotNull VcsRoot toRoot,
                                               @NotNull RepositoryStateData toState,
                                               @NotNull CheckoutRules checkoutRules) throws VcsException {
    return collectChanges(toRoot, fromState, toState, checkoutRules);
  }

  @NotNull
  public List<ModificationData> collectChanges(@NotNull VcsRoot root,
                                               @NotNull RepositoryStateData fromState,
                                               @NotNull RepositoryStateData toState,
                                               @NotNull CheckoutRules checkoutRules) throws VcsException {
    OperationContext context = myVcs.createContext(root, "collecting changes", createProgress());
    GitVcsRoot gitRoot = context.getGitRoot();
    return myRepositoryManager.runWithDisabledRemove(gitRoot.getRepositoryDir(), () -> {
      List<ModificationData> changes = new ArrayList<ModificationData>();
      try {
        Repository r = context.getRepository();
        ModificationDataRevWalk revWalk = new ModificationDataRevWalk(myConfig, context);
        revWalk.sort(RevSort.TOPO);
        ensureRepositoryStateLoadedFor(context, fromState, toState);
        markStart(r, revWalk, toState);
        markUninteresting(r, revWalk, fromState, toState);
        while (revWalk.next() != null) {
          changes.add(revWalk.createModificationData());
        }
      } catch (Exception e) {
        if (e instanceof SubmoduleException) {
          SubmoduleException se = (SubmoduleException) e;
          Set<String> affectedBranches = getBranchesWithCommit(context.getRepository(), toState, se.getMainRepositoryCommit());
          throw context.wrapException(se.addBranches(affectedBranches));
        }
        throw context.wrapException(e);
      } finally {
        context.close();
      }
      return changes;
    });
  }

  @NotNull
  public Result getLatestRevisionAcceptedByCheckoutRules(@NotNull VcsRoot root,
                                                         @NotNull CheckoutRules rules,
                                                         @NotNull String startRevision,
                                                         @NotNull String startRevisionBranchName,
                                                         @NotNull Collection<String> stopRevisions) throws VcsException {
    return getLatestRevisionAcceptedByCheckoutRules(root, rules, startRevision, startRevisionBranchName, stopRevisions, null);
  }

  @NotNull
  public Result getLatestRevisionAcceptedByCheckoutRules(@NotNull VcsRoot root,
                                                         @NotNull CheckoutRules rules,
                                                         @NotNull String startRevision,
                                                         @NotNull String startRevisionBranchName,
                                                         @NotNull Collection<String> stopRevisions,
                                                         @Nullable Set<String> visited) throws VcsException {


    Disposable name = NamedDaemonThreadFactory.patchThreadName("Computing the latest commit affected by checkout rules: " + rules +
                                                               " in VCS root: " + LogUtil.describe(root) + ", start revision: " + startRevision + " (branch: " + startRevisionBranchName + "), stop revisions: " + stopRevisions);
    try {
      OperationContext context = myVcs.createContext(root, "latest revision affecting checkout", createProgress());
      GitVcsRoot gitRoot = context.getGitRoot();
      return myRepositoryManager.runWithDisabledRemove(gitRoot.getRepositoryDir(), () -> {
        try {
          new FetchContext(context, myVcs).withRevisions(Collections.singletonMap(startRevisionBranchName, startRevision), false).fetchIfNoCommitsOrFail();
        } catch (Throwable e) {
          // this exception should not happen here but we'll handle it just for the case
          LOG.warnAndDebugDetails("Could not find the start revision " + startRevision + " in the branch " + startRevisionBranchName, e);
        }

        CheckoutRulesRevWalk revWalk = null;
        try {
          revWalk = new CheckoutRulesRevWalk(myConfig, context, rules);
          List<RevCommit> startCommits = getCommits(revWalk.getRepository(), revWalk, Collections.singleton(startRevision));
          if (startCommits.isEmpty()) {
            LOG.warn("Could not find the start revision " + startRevision + " in the repository at path: " + gitRoot.getRepositoryDir());
            return new Result(null, Collections.emptyList());
          }

          revWalk.markStart(startCommits.get(0));
          revWalk.setStopRevisions(stopRevisions);

          String result;
          RevCommit foundCommit = revWalk.getNextMatchedCommit();
          if (foundCommit != null) {
            result = foundCommit.name();
          } else {
            result = revWalk.getClosestPartiallyAffectedMergeCommit();
          }
          if (visited != null) {
            visited.addAll(revWalk.getVisitedRevisions());
          }

          return new Result(result, revWalk.getReachedStopRevisions());
        } catch (Exception e) {
          throw context.wrapException(e);
        } finally {
          if (revWalk != null) {
            revWalk.close();
            revWalk.dispose();
          }
          context.close();
        }
      });
    } finally {
      name.dispose();
    }
  }

  @NotNull
  private Set<String> getBranchesWithCommit(@NotNull Repository r, @NotNull RepositoryStateData state, @NotNull String commit) {
    return Collections.emptySet();
  }


  @NotNull
  public RepositoryStateData getCurrentState(@NotNull VcsRoot root) throws VcsException {
    return myVcs.getCurrentState(root);
  }

  public void ensureRepositoryStateLoadedFor(@NotNull final OperationContext context,
                                             @NotNull final RepositoryStateData state,
                                             boolean throwErrors) throws Exception {
    new FetchContext(context, myVcs)
      .withRevisions(state.getBranchRevisions(), throwErrors)
      .fetchIfNoCommitsOrFail();
  }

  public void ensureRepositoryStateLoadedFor(@NotNull final OperationContext context,
                                             @NotNull final RepositoryStateData fromState,
                                             @NotNull final RepositoryStateData toState) throws Exception {
    new FetchContext(context, myVcs)
      .withToRevisions(toState.getBranchRevisions())
      .withFromRevisions(fromState.getBranchRevisions())
      .fetchIfNoCommitsOrFail();
  }

  @NotNull
  public RepositoryStateData fetchAllRefs(@NotNull final OperationContext context,
                                          @NotNull final GitVcsRoot root) throws VcsException {
    try {
      final RepositoryStateData currentState = myVcs.getCurrentState(root);
      new FetchContext(context, myVcs).withFromRevisions(currentState.getBranchRevisions()).fetchIfNoCommitsOrFail();
      return currentState;
    } catch (Exception e) {
      throw new VcsException(e.getMessage(), e);
    }
  }

  private void markUninteresting(@NotNull Repository r,
                                 @NotNull ModificationDataRevWalk walk,
                                 @NotNull final RepositoryStateData fromState,
                                 @NotNull final RepositoryStateData toState) throws IOException {
    List<RevCommit> commits = getCommits(fromState, r, walk);
    if (commits.isEmpty()) {//if non of fromState revisions found - limit commits by toState
      commits = getCommits(toState, r, walk);
      LOG.info("Cannot find commits referenced by fromState, will not report any changes");
    }
    for (RevCommit commit : commits) {
      walk.markUninteresting(commit);
    }
  }


  private void markStart(@NotNull Repository r, @NotNull RevWalk walk, @NotNull RepositoryStateData state) throws IOException {
    walk.markStart(getCommits(state, r, walk));
  }


  @NotNull
  private List<RevCommit> getCommits(@NotNull RepositoryStateData state, @NotNull Repository r, @NotNull RevWalk walk) throws IOException {
    final Collection<String> revisions = state.getBranchRevisions().values();

    return getCommits(r, walk, revisions);
  }

  @NotNull
  private List<RevCommit> getCommits(final @NotNull Repository r, final @NotNull RevWalk walk, @NotNull final Collection<String> revisions)
    throws IOException {
    List<RevCommit> result = new ArrayList<>();
    for (String revision : revisions) {
      ObjectId id = ObjectId.fromString(GitUtils.versionRevision(revision));
      if (r.getObjectDatabase().has(id)) {
        RevObject obj = walk.parseAny(id);
        if (obj.getType() == Constants.OBJ_COMMIT)
          result.add((RevCommit) obj);
      }
    }
    return result;
  }


  @NotNull
  private GitProgress createProgress() {
    try {
      return new GitVcsOperationProgress(myProgressProvider.getProgress());
    } catch (IllegalStateException e) {
      return GitProgress.NO_OP;
    }
  }
}
