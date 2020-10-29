/*
 * IdeaVim - Vim emulator for IDEs based on the IntelliJ platform
 * Copyright (C) 2003-2020 The IdeaVim authors
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

@file:JvmName("EditorHelperRt")

package com.maddyhome.idea.vim.helper

import com.intellij.ide.ui.laf.darcula.DarculaUIUtil
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.VisualPosition
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.maddyhome.idea.vim.option.OptionsManager
import kotlin.system.measureTimeMillis

val Editor.fileSize: Int
  get() = document.textLength

/**
 * There is a problem with one-line editors. At the moment of the editor creation, this property is always set to false.
 *   So, we should enable IdeaVim for such editors and disable it on the first interaction
 */
val Editor.isIdeaVimDisabledHere: Boolean
  get() {
    var res = true
    val start = System.currentTimeMillis()
    val times = mutableListOf<Long>()
    val timeForCalculation = measureTimeMillis {
      res = (disabledInDialog.apply { times += System.currentTimeMillis() }
        || (!OptionsManager.ideaenabledbufs.contains("singleline") && isDatabaseCell).apply { times += System.currentTimeMillis() }
        || (!OptionsManager.ideaenabledbufs.contains("singleline") && isOneLineMode).apply { times += System.currentTimeMillis() }
        )
    }
    if (timeForCalculation > 10) {
      val timeDiffs = times.map { it - start }
      val message = "Time for calculation of 'isIdeaVimDisabledHere' took $timeForCalculation ms. Time diff: $timeDiffs"
      logger<Editor>().error(message)
    }
    return res
  }

private val Editor.isDatabaseCell: Boolean
  get() = DarculaUIUtil.isTableCellEditor(this.component)

private val Editor.disabledInDialog: Boolean
  get() = (!OptionsManager.ideaenabledbufs.contains("dialog") && !OptionsManager.ideaenabledbufs.contains("dialoglegacy"))
    && (!this.isPrimaryEditor() && !EditorHelper.isFileEditor(this))

/**
 * Checks if the editor is a primary editor in the main editing area.
 */
fun Editor.isPrimaryEditor(): Boolean {
  val project = project ?: return false
  val fileEditorManager = FileEditorManagerEx.getInstanceEx(project) ?: return false
  return fileEditorManager.allEditors.any { fileEditor -> this == EditorUtil.getEditorEx(fileEditor) }
}

