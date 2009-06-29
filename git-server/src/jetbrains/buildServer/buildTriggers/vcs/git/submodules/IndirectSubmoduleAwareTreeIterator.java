/*
 * Copyright 2000-2009 JetBrains s.r.o.
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

import org.spearce.jgit.errors.CorruptObjectException;
import org.spearce.jgit.treewalk.AbstractTreeIterator;

import java.io.IOException;

/**
 * Indirect submodule-aware tree iterator. This iterator for the cases when directory entry reordering is needed.
 * This is caused by the fact that submodules are directories are treated differently with respect to the sorting.
 * The submodules are ordered like the following:
 * <ul>
 * <li>a</li>
 * <li>a.c</li>
 * <li>a0c</li>
 * </ul>
 * And the directories are ordered as the following:
 * <ul>
 * <li>a.c</li>
 * <li>a/c</li>
 * <li>a0c</li>
 * </ul>
 * Because of this, when iterpreting submodules are directories an reordering is needed.
 */
public class IndirectSubmoduleAwareTreeIterator extends SubmoduleAwareTreeIterator {
  /**
   * The current poistion
   */
  private int myPosition = 0;
  /**
   * The offset mapping
   */
  private final int[] myMapping;


  /**
   * The constructor
   *
   * @param wrappedIterator   the wrapped iterator
   * @param submoduleResolver the resolver for submodules
   * @param mapping           the mapping of poistions
   * @throws IOException in case of IO problem
   */
  public IndirectSubmoduleAwareTreeIterator(AbstractTreeIterator wrappedIterator, SubmoduleResolver submoduleResolver, int[] mapping)
    throws IOException {
    super(wrappedIterator, submoduleResolver);
    myMapping = mapping;
    adjustStartPosition();
  }


  /**
   * The constructor
   *
   * @param parent            the parent iterator
   * @param wrappedIterator   the wrapped iterator
   * @param submoduleResolver the resolver for submodules
   * @param mapping           the mapping of poistions
   * @throws CorruptObjectException in case of navigation error
   */
  public IndirectSubmoduleAwareTreeIterator(SubmoduleAwareTreeIterator parent,
                                            AbstractTreeIterator wrappedIterator, SubmoduleResolver submoduleResolver, int[] mapping)
    throws CorruptObjectException {
    super(parent, wrappedIterator, submoduleResolver);
    myMapping = mapping;
    adjustStartPosition();
  }

  /**
   * Adjust start position for the mapping
   *
   * @throws CorruptObjectException in case of navigation error
   */
  private void adjustStartPosition() throws CorruptObjectException {
    if (myMapping[0] != 0) {
      for (int i = 0; i < myMapping.length; i++) {
        if (myMapping[i] == 0) {
          myPosition = i;
          break;
        }
      }
      assert myPosition != 0;
      back(myPosition);
    }
  }


  /**
   * {@inheritDoc}
   */
  public boolean first() {
    return myPosition == 0;
  }

  /**
   * {@inheritDoc}
   */
  public boolean eof() {
    return myMapping == null ? myWrappedIterator.eof() : myPosition == myMapping.length;
  }

  /**
   * {@inheritDoc}
   */
  public void next(int delta) throws CorruptObjectException {
    if (delta <= 0) {
      throw new IllegalArgumentException("Delta must be positive: " + delta);
    }
    if (myPosition + delta > myMapping.length) {
      delta = myMapping.length - myPosition;
    }
    if (delta == 0) {
      return;
    }
    move(delta);
  }

  /**
   * Move to the position specified by the offset in array
   *
   * @param offset the positive or negative offset to move by
   * @throws CorruptObjectException in case of naviagation problems
   */
  private void move(int offset) throws CorruptObjectException {
    int newPosition = myPosition + offset;
    int actualDelta = translatePosition(newPosition) - translatePosition(myPosition);
    assert actualDelta != 0;
    if (actualDelta < 0) {
      myWrappedIterator.back(-actualDelta);
    } else {
      myWrappedIterator.next(actualDelta);
    }
    myPosition = newPosition;
    movedToEntry();
  }

  /**
   * Translate position using mapping
   *
   * @param position a position to translate
   * @return the translated positions
   */
  private int translatePosition(int position) {
    return position >= myMapping.length ? position : myMapping[position];
  }

  /**
   * {@inheritDoc}
   */
  public void back(int delta) throws CorruptObjectException {
    if (delta <= 0) {
      throw new IllegalArgumentException("Delta must be positive: " + delta);
    }
    if (myPosition - delta < 0) {
      delta = myPosition;
    }
    if (delta == 0) {
      return;
    }
    move(-delta);
  }

}