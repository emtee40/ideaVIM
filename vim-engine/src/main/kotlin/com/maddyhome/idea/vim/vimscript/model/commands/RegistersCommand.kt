/*
 * IdeaVim - Vim emulator for IDEs based on the IntelliJ platform
 * Copyright (C) 2003-2022 The IdeaVim authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.maddyhome.idea.vim.vimscript.model.commands

import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.SelectionType
import com.maddyhome.idea.vim.ex.ranges.Ranges
import com.maddyhome.idea.vim.helper.EngineStringHelper
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult

/**
 * see "h :registers" / "h :display"
 */
data class RegistersCommand(val ranges: Ranges, val argument: String) : Command.SingleExecution(ranges, argument) {
  override val argFlags = flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_OPTIONAL, Access.READ_ONLY)
  override fun processCommand(editor: VimEditor, context: ExecutionContext): ExecutionResult {

    val registerGroup = injector.registerGroup
    val regs = registerGroup.getRegisters()
      .filter { argument.isEmpty() || argument.contains(it.name) }
      .joinToString("\n", prefix = "Type Name Content\n") { reg ->
        val type = when (reg.type) {
          SelectionType.LINE_WISE -> "l"
          SelectionType.CHARACTER_WISE -> "c"
          SelectionType.BLOCK_WISE -> "b"
        }
        val text = reg.rawText?.let { injector.parser.parseKeys(it) } ?: reg.keys
        "  $type  \"${reg.name}   ${EngineStringHelper.toPrintableCharacters(text).take(200)}"
      }

    injector.exOutputPanel.getPanel(editor).output(regs)

    return ExecutionResult.Success
  }
}