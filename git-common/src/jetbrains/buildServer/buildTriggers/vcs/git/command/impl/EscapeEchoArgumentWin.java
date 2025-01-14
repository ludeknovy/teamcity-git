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

package jetbrains.buildServer.buildTriggers.vcs.git.command.impl;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class EscapeEchoArgumentWin implements EscapeEchoArgument {

  @NotNull
  public String escape(@Nullable String s) {
    if (s == null)
      s = "";
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '&':
        case '^':
        case '<':
        case '>':
        case '|':
        case '"':
          sb.append("^");
          sb.append(c);
          break;
        case '%':
          sb.append("%");
          sb.append(c);
          break;
        default:
          sb.append(c);
          break;
      }
    }
    return sb.toString();
  }

}
