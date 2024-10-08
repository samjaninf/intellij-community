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
package com.intellij.dvcs.push.ui;


import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.util.ui.tree.TreeUtil;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeNode;

@ApiStatus.Internal
public class TextWithLinkNode extends DefaultMutableTreeNode implements CustomRenderedTreeNode {

  @NotNull protected VcsLinkedTextComponent myLinkedText;

  public TextWithLinkNode(@NotNull VcsLinkedTextComponent linkedText) {
    myLinkedText = linkedText;
  }

  @Override
  public void render(@NotNull ColoredTreeCellRenderer renderer) {
    renderer.append("   ");
    myLinkedText.setSelected(renderer.getTree().isPathSelected(TreeUtil.getPathFromRoot(this)));
    TreeNode parent = getParent();
    if (parent instanceof RepositoryNode) {
      myLinkedText.setTransparent(!((RepositoryNode)parent).isChecked());
    }
    myLinkedText.render(renderer);
  }
}