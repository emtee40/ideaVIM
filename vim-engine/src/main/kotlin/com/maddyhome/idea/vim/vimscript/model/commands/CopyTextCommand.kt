/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */

package com.maddyhome.idea.vim.vimscript.model.commands

import com.intellij.vim.annotations.ExCommand
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.getText
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.command.SelectionType
import com.maddyhome.idea.vim.command.VimStateMachine
import com.maddyhome.idea.vim.common.Direction
import com.maddyhome.idea.vim.ex.ExException
import com.maddyhome.idea.vim.ex.ranges.Ranges
import com.maddyhome.idea.vim.put.TextData
import com.maddyhome.idea.vim.put.ToLinePasteOptions
import com.maddyhome.idea.vim.vimscript.model.ExecutionResult

/**
 * see "h :copy"
 */
@ExCommand(command = "t,co[py]")
public data class CopyTextCommand(val ranges: Ranges, val argument: String) : Command.SingleExecution(ranges, argument) {
  override val argFlags: CommandHandlerFlags = flags(RangeFlag.RANGE_OPTIONAL, ArgumentFlag.ARGUMENT_REQUIRED, Access.WRITABLE)

  override fun processCommand(editor: VimEditor, context: ExecutionContext, operatorArguments: OperatorArguments): ExecutionResult {
    val carets = editor.sortedCarets()
    for (caret in carets) {
      val range = getTextRange(editor, caret, false)
      val text = editor.getText(range)

      val goToLineCommand = injector.vimscriptParser.parseCommand(argument) ?: throw ExException("E16: Invalid range")
      val line = goToLineCommand.commandRanges.getFirstLine(editor, caret)

      val transferableData = injector.clipboardManager.getTransferableData(editor, range, text)
      val textData = TextData(text, SelectionType.LINE_WISE, transferableData, null)
      val insertedRange = injector.put.putTextForCaretNonVisual(
        caret,
        context,
        textData,
        ToLinePasteOptions(line),
        ) ?: throw ExException("Failed to perform paste")
      caret.moveToTextRange(insertedRange.range, textData.typeInRegister, VimStateMachine.SubMode.NONE, Direction.BACKWARDS)
    }
    return ExecutionResult.Success
  }
}
