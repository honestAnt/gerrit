// Copyright (C) 2012 The Android Open Source Project
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

package com.google.gerrit.server.git.strategy;

import static com.google.common.base.Preconditions.checkState;
import static com.google.gerrit.server.git.strategy.CommitMergeStatus.SKIPPED_IDENTICAL_TREE;


import com.google.common.collect.ImmutableList;
import com.google.gerrit.extensions.restapi.MergeConflictException;
import com.google.gerrit.extensions.restapi.ResourceConflictException;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.server.ChangeUtil;
import com.google.gerrit.server.change.RebaseChangeOp;
import com.google.gerrit.server.git.BatchUpdate.ChangeContext;
import com.google.gerrit.server.git.BatchUpdate.Context;
import com.google.gerrit.server.git.BatchUpdate.RepoContext;
import com.google.gerrit.server.git.CodeReviewCommit;
import com.google.gerrit.server.git.IntegrationException;
import com.google.gerrit.server.git.MergeIdenticalTreeException;
import com.google.gerrit.server.git.MergeTip;
import com.google.gerrit.server.git.RebaseSorter;
import com.google.gerrit.server.git.validators.CommitValidators;
import com.google.gerrit.server.project.InvalidChangeOperationException;
import com.google.gerrit.server.project.NoSuchChangeException;
import com.google.gwtorm.server.OrmException;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.transport.ReceiveCommand;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * This strategy covers RebaseAlways and RebaseIfNecessary ones.
 */
public class RebaseSubmitStrategy extends SubmitStrategy {
  private final boolean rebaseAlways;

  RebaseSubmitStrategy(SubmitStrategy.Arguments args, boolean rebaseAlways) {
    super(args);
    this.rebaseAlways = rebaseAlways;
  }

  @Override
  public List<SubmitStrategyOp> buildOps(
      Collection<CodeReviewCommit> toMerge) throws IntegrationException {
    List<CodeReviewCommit> sorted = sort(toMerge);
    List<SubmitStrategyOp> ops = new ArrayList<>(sorted.size());
    boolean first = true;

    for (CodeReviewCommit c : sorted) {
      if (c.getParentCount() > 1) {
        // Since there is a merge commit, sort and prune again using
        // MERGE_IF_NECESSARY semantics to avoid creating duplicate
        // commits.
        //
        sorted = args.mergeUtil.reduceToMinimalMerge(args.mergeSorter, sorted);
        break;
      }
    }

    while (!sorted.isEmpty()) {
      CodeReviewCommit n = sorted.remove(0);
      if (first && args.mergeTip.getInitialTip() == null) {
        // TODO(tandrii): Cherry-Pick strategy does this too, but it's wrong
        // and can be fixed.
        ops.add(new FastForwardOp(args, n));
      } else if (n.getParentCount() == 0) {
        ops.add(new RebaseRootOp(n));
      } else if (n.getParentCount() == 1) {
        ops.add(new RebaseOneOp(n));
      } else {
        ops.add(new RebaseMultipleParentsOp(n));
      }
      first = false;
    }
    return ops;
  }

  private class RebaseRootOp extends SubmitStrategyOp {
    private RebaseRootOp(CodeReviewCommit toMerge) {
      super(RebaseSubmitStrategy.this.args, toMerge);
    }

    @Override
    public void updateRepoImpl(RepoContext ctx) {
      // Refuse to merge a root commit into an existing branch, we cannot obtain
      // a delta for the cherry-pick to apply.
      toMerge.setStatusCode(CommitMergeStatus.CANNOT_REBASE_ROOT);
    }
  }

  private class RebaseOneOp extends SubmitStrategyOp {
    private RebaseChangeOp rebaseOp;
    private CodeReviewCommit newCommit;
    private PatchSet.Id newPatchSetId;

    private RebaseOneOp(CodeReviewCommit toMerge) {
      super(RebaseSubmitStrategy.this.args, toMerge);
    }

    @Override
    public void updateRepoImpl(RepoContext ctx)
        throws IntegrationException, InvalidChangeOperationException,
        RestApiException, IOException, OrmException {
      // TODO(dborowitz): args.rw is needed because it's a CodeReviewRevWalk.
      // When hoisting BatchUpdate into MergeOp, we will need to teach
      // BatchUpdate how to produce CodeReviewRevWalks.
      if (args.mergeUtil
          .canFastForward(args.mergeSorter, args.mergeTip.getCurrentTip(),
              args.rw, toMerge)) {
        if (!rebaseAlways){
          args.mergeTip.moveTipTo(amendGitlink(toMerge), toMerge);
          toMerge.setStatusCode(CommitMergeStatus.CLEAN_MERGE);
          acceptMergeTip(args.mergeTip);
          return;
        }
        // RebaseAlways means we modify commit message.
        args.rw.parseBody(toMerge);
        newPatchSetId = ChangeUtil.nextPatchSetId(
            args.repo, toMerge.change().currentPatchSetId());
        // TODO(tandrii): add extension point to customize this commit message.
        String cherryPickCmtMsg =
            args.mergeUtil.createCherryPickCommitMessage(toMerge);

        PersonIdent committer = args.caller.newCommitterIdent(ctx.getWhen(),
            args.serverIdent.getTimeZone());
        try {
          newCommit = args.mergeUtil.createCherryPickFromCommit(args.repo,
              args.inserter, args.mergeTip.getCurrentTip(), toMerge, committer,
              cherryPickCmtMsg, args.rw, 0);
        } catch (MergeConflictException mce) {
          // Unlike in Cherry-pick case, this should never happen.
          toMerge.setStatusCode(CommitMergeStatus.REBASE_MERGE_CONFLICT);
          throw new IllegalStateException(
              "MergeConflictException on message edit must not happen");
        } catch (MergeIdenticalTreeException mie) {
          toMerge.setStatusCode(SKIPPED_IDENTICAL_TREE);
          return;
        }
        ctx.addRefUpdate(new ReceiveCommand(ObjectId.zeroId(), newCommit,
            newPatchSetId.toRefName()));
      } else {
        // Stale read of patch set is ok; see comments in RebaseChangeOp.
        PatchSet origPs = args.psUtil.get(ctx.getDb(),
            toMerge.getControl().getNotes(), toMerge.getPatchsetId());
        // TODO(tandrii): add extension point to customize commit message while
        // rebasing.
        rebaseOp = args.rebaseFactory.create(
              toMerge.getControl(), origPs, args.mergeTip.getCurrentTip().name())
            .setFireRevisionCreated(false)
            // Bypass approval copier since SubmitStrategyOp copy all approvals
            // later anyway.
            .setCopyApprovals(false)
            .setValidatePolicy(CommitValidators.Policy.NONE)
            // Do not post message after inserting new patchset because there
            // will be one about change being merged already.
            .setPostMessage(false);
        try {
          rebaseOp.updateRepo(ctx);
        } catch (MergeConflictException | NoSuchChangeException e) {
          toMerge.setStatusCode(CommitMergeStatus.REBASE_MERGE_CONFLICT);
          throw new IntegrationException(
              "Cannot rebase " + toMerge.name() + ": " + e.getMessage(), e);
        }
        newCommit = args.rw.parseCommit(rebaseOp.getRebasedCommit());
        newPatchSetId = rebaseOp.getPatchSetId();
      }
      newCommit = amendGitlink(newCommit);
      newCommit.copyFrom(toMerge);
      newCommit.setPatchsetId(newPatchSetId);
      newCommit.setStatusCode(CommitMergeStatus.CLEAN_REBASE);
      args.mergeTip.moveTipTo(newCommit, newCommit);
      args.commits.put(args.mergeTip.getCurrentTip());
      acceptMergeTip(args.mergeTip);
    }

    @Override
    public PatchSet updateChangeImpl(ChangeContext ctx)
        throws NoSuchChangeException, ResourceConflictException,
        OrmException, IOException  {
      if (newCommit == null) {
        checkState(!rebaseAlways, "RebaseAlways must never fast forward");
        // Took the fast-forward option, nothing to do.
        return null;
      }
      PatchSet newPs;
      if (rebaseOp != null) {
        rebaseOp.updateChange(ctx);
        newPs = rebaseOp.getPatchSet();
      } else {
        // CherryPick
        PatchSet prevPs = args.psUtil.current(ctx.getDb(), ctx.getNotes());
        newPs = args.psUtil.insert(ctx.getDb(), ctx.getRevWalk(),
            ctx.getUpdate(newPatchSetId), newPatchSetId, newCommit, false,
            prevPs != null ? prevPs.getGroups() : ImmutableList.<String> of(),
            null);
      }
      ctx.getChange().setCurrentPatchSet(args.patchSetInfoFactory
          .get(ctx.getRevWalk(), newCommit, newPatchSetId));
      newCommit.setControl(ctx.getControl());
      return newPs;
    }

    @Override
    public void postUpdateImpl(Context ctx) throws OrmException {
      if (rebaseOp != null) {
        rebaseOp.postUpdate(ctx);
      }
    }
  }

  private class RebaseMultipleParentsOp extends SubmitStrategyOp {
    private RebaseMultipleParentsOp(CodeReviewCommit toMerge) {
      super(RebaseSubmitStrategy.this.args, toMerge);
    }

    @Override
    public void updateRepoImpl(RepoContext ctx)
        throws IntegrationException, IOException {
      // There are multiple parents, so this is a merge commit. We don't want
      // to rebase the merge as clients can't easily rebase their history with
      // that merge present and replaced by an equivalent merge with a different
      // first parent. So instead behave as though MERGE_IF_NECESSARY was
      // configured.
      // TODO(tandrii): this is not in spirit of RebaseAlways strategy because
      // the commit messages can not be modified in the process. It's also
      // possible to implement rebasing of merge commits. E.g., the Cherry Pick
      // REST endpoint already supports cherry-picking of merge commits.
      // For now, users of RebaseAlways strategy for whom changed commit footers
      // are important would be well advised to prohibit uploading patches with
      // merge commits.
      MergeTip mergeTip = args.mergeTip;
      if (args.rw.isMergedInto(mergeTip.getCurrentTip(), toMerge) &&
          !args.submoduleOp.hasSubscription(args.destBranch)) {
        mergeTip.moveTipTo(toMerge, toMerge);
      } else {
        CodeReviewCommit newTip = args.mergeUtil.mergeOneCommit(
            args.serverIdent, args.serverIdent, args.repo, args.rw,
            args.inserter, args.destBranch, mergeTip.getCurrentTip(), toMerge);
        mergeTip.moveTipTo(amendGitlink(newTip), toMerge);
      }
      args.mergeUtil.markCleanMerges(args.rw, args.canMergeFlag,
          mergeTip.getCurrentTip(), args.alreadyAccepted);
      acceptMergeTip(mergeTip);
    }
  }

  private void acceptMergeTip(MergeTip mergeTip) {
    args.alreadyAccepted.add(mergeTip.getCurrentTip());
  }

  private List<CodeReviewCommit> sort(Collection<CodeReviewCommit> toSort)
      throws IntegrationException {
    try {
      return new RebaseSorter(
          args.rw, args.alreadyAccepted, args.canMergeFlag).sort(toSort);
    } catch (IOException e) {
      throw new IntegrationException("Commit sorting failed", e);
    }
  }

  static boolean dryRun(SubmitDryRun.Arguments args,
      CodeReviewCommit mergeTip, CodeReviewCommit toMerge)
      throws IntegrationException {
    // Test for merge instead of cherry pick to avoid false negatives
    // on commit chains.
    return !args.mergeUtil.hasMissingDependencies(args.mergeSorter, toMerge)
        && args.mergeUtil.canMerge(args.mergeSorter, args.repo, mergeTip,
             toMerge);
  }
}
