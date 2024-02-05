/*
 * Copyright 2003-2023 The IdeaVim authors
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE.txt file or at
 * https://opensource.org/licenses/MIT.
 */
package com.maddyhome.idea.vim

import com.maddyhome.idea.vim.action.change.LazyVimCommand
import com.maddyhome.idea.vim.api.ExecutionContext
import com.maddyhome.idea.vim.api.VimEditor
import com.maddyhome.idea.vim.api.globalOptions
import com.maddyhome.idea.vim.api.injector
import com.maddyhome.idea.vim.command.Argument
import com.maddyhome.idea.vim.command.Command
import com.maddyhome.idea.vim.command.CommandBuilder
import com.maddyhome.idea.vim.command.CommandFlags
import com.maddyhome.idea.vim.command.MappingMode
import com.maddyhome.idea.vim.command.MappingProcessor
import com.maddyhome.idea.vim.command.OperatorArguments
import com.maddyhome.idea.vim.common.CurrentCommandState
import com.maddyhome.idea.vim.common.DigraphResult
import com.maddyhome.idea.vim.common.argumentCaptured
import com.maddyhome.idea.vim.diagnostic.VimLogger
import com.maddyhome.idea.vim.diagnostic.debug
import com.maddyhome.idea.vim.diagnostic.trace
import com.maddyhome.idea.vim.diagnostic.vimLogger
import com.maddyhome.idea.vim.handler.EditorActionHandlerBase
import com.maddyhome.idea.vim.helper.isCloseKeyStroke
import com.maddyhome.idea.vim.helper.vimStateMachine
import com.maddyhome.idea.vim.impl.state.toMappingMode
import com.maddyhome.idea.vim.key.CommandNode
import com.maddyhome.idea.vim.key.CommandPartNode
import com.maddyhome.idea.vim.key.KeyStack
import com.maddyhome.idea.vim.key.Node
import com.maddyhome.idea.vim.state.KeyHandlerState
import com.maddyhome.idea.vim.state.VimStateMachine
import com.maddyhome.idea.vim.state.mode.Mode
import com.maddyhome.idea.vim.state.mode.ReturnTo
import com.maddyhome.idea.vim.state.mode.ReturnableFromCmd
import com.maddyhome.idea.vim.state.mode.returnTo
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.KeyStroke

/**
 * This handles every keystroke that the user can argType except those that are still valid hotkeys for various Idea
 * actions. This is a singleton.
 */
public class KeyHandler {
  public var keyHandlerState: KeyHandlerState = KeyHandlerState()
    private set

  private var handleKeyRecursionCount = 0

  public val keyStack: KeyStack = KeyStack()
  public val modalEntryKeys: MutableList<KeyStroke> = ArrayList()

  /**
   * This is the main key handler for the Vim plugin. Every keystroke not handled directly by Idea is sent here for
   * processing.
   *
   * @param editor  The editor the key was typed into
   * @param key     The keystroke typed by the user
   * @param context The data context
   */
  public fun handleKey(editor: VimEditor, key: KeyStroke, context: ExecutionContext, keyState: KeyHandlerState? = null) {
    if (keyState == null) {
      handleKey(editor, key, context, allowKeyMappings = true, mappingCompleted = false)
    } else {
      handleKey(editor, key, context, allowKeyMappings = true, mappingCompleted = false, keyState)
    }
  }

  /**
   * Handling input keys with additional parameters
   *
   * @param allowKeyMappings - If we allow key mappings or not
   * @param mappingCompleted - if true, we don't check if the mapping is incomplete
   *
   * TODO mappingCompleted and recursionCounter - we should find a more beautiful way to use them
   * TODO it should not receive editor at all and use the focused one. It will help to execute macro between multiple editors
   */
  public fun handleKey(
    editor: VimEditor,
    key: KeyStroke,
    context: ExecutionContext,
    allowKeyMappings: Boolean,
    mappingCompleted: Boolean,
    keyState: KeyHandlerState? = null,
  ) {
    LOG.trace {
      """
        ------- Key Handler -------
        Start key processing. allowKeyMappings: $allowKeyMappings, mappingCompleted: $mappingCompleted
        Key: $key
      """.trimIndent()
    }
    val maxMapDepth = injector.globalOptions().maxmapdepth
    if (handleKeyRecursionCount >= maxMapDepth) {
      injector.messages.showStatusBarMessage(editor, injector.messages.message("E223"))
      injector.messages.indicateError()
      LOG.warn("Key handling, maximum recursion of the key received. maxdepth=$maxMapDepth")
      return
    }

    val newState = keyState ?: this.keyHandlerState
    injector.messages.clearError()
    val editorState = editor.vimStateMachine
    val commandBuilder = newState.commandBuilder

    // If this is a "regular" character keystroke, get the character
    val chKey: Char = if (key.keyChar == KeyEvent.CHAR_UNDEFINED) 0.toChar() else key.keyChar

    // We only record unmapped keystrokes. If we've recursed to handle mapping, don't record anything.
    var shouldRecord = handleKeyRecursionCount == 0 && injector.registerGroup.isRecording
    handleKeyRecursionCount++
    try {
      LOG.trace("Start key processing...")
      if (!allowKeyMappings || !MappingProcessor.handleKeyMapping(editor, key, newState, context, mappingCompleted)) {
        LOG.trace("Mappings processed, continue processing key.")
        if (isCommandCountKey(chKey, newState, editorState)) {
          commandBuilder.addCountCharacter(key)
        } else if (isDeleteCommandCountKey(key, newState, editorState.mode)) {
          commandBuilder.deleteCountCharacter()
        } else if (isEditorReset(key, editorState)) {
          handleEditorReset(editor, key, newState, context)
        } else if (isExpectingCharArgument(commandBuilder)) {
          handleCharArgument(key, chKey, newState, editor)
        } else if (editorState.isRegisterPending) {
          LOG.trace("Pending mode.")
          commandBuilder.addKey(key)
          handleSelectRegister(editorState, chKey, newState)
        } else if (!handleDigraph(editor, key, newState, context)) {
          LOG.debug("Digraph is NOT processed")

          // Ask the key/action tree if this is an appropriate key at this point in the command and if so,
          // return the node matching this keystroke
          val node: Node<LazyVimCommand>? = mapOpCommand(key, commandBuilder.getChildNode(key), editorState.mode, newState)
          LOG.trace("Get the node for the current mode")

          if (node is CommandNode<LazyVimCommand>) {
            LOG.trace("Node is a command node")
            handleCommandNode(editor, context, key, node, newState, editorState)
            commandBuilder.addKey(key)
          } else if (node is CommandPartNode<LazyVimCommand>) {
            LOG.trace("Node is a command part node")
            commandBuilder.setCurrentCommandPartNode(node)
            commandBuilder.addKey(key)
          } else if (isSelectRegister(key, newState, editorState)) {
            LOG.trace("Select register")
            editorState.isRegisterPending = true
            commandBuilder.addKey(key)
          } else {
            // node == null
            LOG.trace("We are not able to find a node for this key")

            // If we are in insert/replace mode send this key in for processing
            if (editorState.mode == Mode.INSERT || editorState.mode == Mode.REPLACE) {
              LOG.trace("Process insert or replace")
              shouldRecord = injector.changeGroup.processKey(editor, context, key) && shouldRecord
            } else if (editorState.mode is Mode.SELECT) {
              LOG.trace("Process select")
              shouldRecord = injector.changeGroup.processKeyInSelectMode(editor, context, key) && shouldRecord
            } else if (editor.mode is Mode.CMD_LINE) {
              LOG.trace("Process cmd line")
              shouldRecord = injector.processGroup.processExKey(editor, key) && shouldRecord
            } else {
              LOG.trace("Set command state to bad_command")
              commandBuilder.commandState = CurrentCommandState.BAD_COMMAND
            }
            partialReset(newState, editorState.mode)
          }
        }
      }
    } finally {
      handleKeyRecursionCount--
    }
    finishedCommandPreparation(editor, context, editorState, key, shouldRecord, newState)
    updateState(newState)
  }

  internal fun finishedCommandPreparation(
    editor: VimEditor,
    context: ExecutionContext,
    editorState: VimStateMachine,
    key: KeyStroke?,
    shouldRecord: Boolean,
    keyState: KeyHandlerState,
  ) {
    // Do we have a fully entered command at this point? If so, let's execute it.
    val commandBuilder = keyState.commandBuilder
    if (commandBuilder.isReady) {
      LOG.trace("Ready command builder. Execute command.")
      executeCommand(editor, context, editorState, keyState)
    } else if (commandBuilder.isBad) {
      LOG.trace("Command builder is set to BAD")
      editor.resetOpPending()
      editorState.resetRegisterPending()
      editor.isReplaceCharacter = false
      injector.messages.indicateError()
      reset(keyState, editorState.mode)
    }

    // Don't record the keystroke that stops the recording (unmapped this is `q`)
    if (shouldRecord && injector.registerGroup.isRecording && key != null) {
      injector.registerGroup.recordKeyStroke(key)
      modalEntryKeys.forEach { injector.registerGroup.recordKeyStroke(it) }
      modalEntryKeys.clear()
    }

    // This will update immediately, if we're on the EDT (which we are)
    injector.messages.updateStatusBar(editor)
    LOG.trace("----------- Key Handler Finished -----------")
  }

  /**
   * See the description for [com.maddyhome.idea.vim.command.DuplicableOperatorAction]
   */
  private fun mapOpCommand(
    key: KeyStroke,
    node: Node<LazyVimCommand>?,
    mode: Mode,
    keyState: KeyHandlerState,
  ): Node<LazyVimCommand>? {
    return if (isDuplicateOperatorKeyStroke(key, mode, keyState)) {
      keyState.commandBuilder.getChildNode(KeyStroke.getKeyStroke('_'))
    } else {
      node
    }
  }

  public fun isDuplicateOperatorKeyStroke(key: KeyStroke, mode: Mode, keyState: KeyHandlerState): Boolean {
    return isOperatorPending(mode, keyState) && keyState.commandBuilder.isDuplicateOperatorKeyStroke(key)
  }

  public fun isOperatorPending(mode: Mode, keyState: KeyHandlerState): Boolean {
    return mode is Mode.OP_PENDING && !keyState.commandBuilder.isEmpty
  }

  private fun handleEditorReset(
    editor: VimEditor,
    key: KeyStroke,
    keyState: KeyHandlerState,
    context: ExecutionContext,
  ) {
    val commandBuilder = keyState.commandBuilder
    if (commandBuilder.isAwaitingCharOrDigraphArgument()) {
      editor.isReplaceCharacter = false
    }
    if (commandBuilder.isAtDefaultState) {
      val register = injector.registerGroup
      if (register.currentRegister == register.defaultRegister) {
        var indicateError = true
        if (key.keyCode == KeyEvent.VK_ESCAPE) {
          val executed = arrayOf<Boolean?>(null)
          injector.actionExecutor.executeCommand(
            editor,
            { executed[0] = injector.actionExecutor.executeEsc(context) },
            "",
            null,
          )
          indicateError = !executed[0]!!
        }
        if (indicateError) {
          injector.messages.indicateError()
        }
      }
    }
    reset(keyState, editor.mode)
  }

  private fun isCommandCountKey(chKey: Char, keyState: KeyHandlerState, editorState: VimStateMachine): Boolean {
    // Make sure to avoid handling '0' as the start of a count.
    val commandBuilder = keyState.commandBuilder
    val notRegisterPendingCommand = editorState.mode is Mode.NORMAL && !editorState.isRegisterPending
    val visualMode = editorState.mode is Mode.VISUAL && !editorState.isRegisterPending
    val opPendingMode = editorState.mode is Mode.OP_PENDING

    if (notRegisterPendingCommand || visualMode || opPendingMode) {
      if (commandBuilder.isExpectingCount && Character.isDigit(chKey) && (commandBuilder.count > 0 || chKey != '0')) {
        LOG.debug("This is a command key count")
        return true
      }
    }
    LOG.debug("This is NOT a command key count")
    return false
  }

  private fun isDeleteCommandCountKey(key: KeyStroke, keyState: KeyHandlerState, mode: Mode): Boolean {
    // See `:help N<Del>`
    val commandBuilder = keyState.commandBuilder
    val isDeleteCommandKeyCount =
      (mode is Mode.NORMAL || mode is Mode.VISUAL || mode is Mode.OP_PENDING) &&
        commandBuilder.isExpectingCount && commandBuilder.count > 0 && key.keyCode == KeyEvent.VK_DELETE

    LOG.debug { "This is a delete command key count: $isDeleteCommandKeyCount" }
    return isDeleteCommandKeyCount
  }

  private fun isEditorReset(key: KeyStroke, editorState: VimStateMachine): Boolean {
    val editorReset = editorState.mode is Mode.NORMAL && key.isCloseKeyStroke()
    LOG.debug { "This is editor reset: $editorReset" }
    return editorReset
  }

  private fun isSelectRegister(key: KeyStroke, keyState: KeyHandlerState, editorState: VimStateMachine): Boolean {
    if (editorState.mode !is Mode.NORMAL && editorState.mode !is Mode.VISUAL) {
      return false
    }
    return if (editorState.isRegisterPending) {
      true
    } else {
      key.keyChar == '"' && !isOperatorPending(editorState.mode, keyState) && keyState.commandBuilder.expectedArgumentType == null
    }
  }

  private fun handleSelectRegister(vimStateMachine: VimStateMachine, chKey: Char, keyState: KeyHandlerState) {
    LOG.trace("Handle select register")
    vimStateMachine.resetRegisterPending()
    if (injector.registerGroup.isValid(chKey)) {
      LOG.trace("Valid register")
      keyState.commandBuilder.pushCommandPart(chKey)
    } else {
      LOG.trace("Invalid register, set command state to BAD_COMMAND")
      keyState.commandBuilder.commandState = CurrentCommandState.BAD_COMMAND
    }
  }

  private fun isExpectingCharArgument(commandBuilder: CommandBuilder): Boolean {
    val expectingCharArgument = commandBuilder.expectedArgumentType === Argument.Type.CHARACTER
    LOG.debug { "Expecting char argument: $expectingCharArgument" }
    return expectingCharArgument
  }

  private fun handleCharArgument(key: KeyStroke, chKey: Char, keyState: KeyHandlerState, editor: VimEditor) {
    var mutableChKey = chKey
    LOG.trace("Handling char argument")
    // We are expecting a character argument - is this a regular character the user typed?
    // Some special keys can be handled as character arguments - let's check for them here.
    if (mutableChKey.code == 0) {
      when (key.keyCode) {
        KeyEvent.VK_TAB -> mutableChKey = '\t'
        KeyEvent.VK_ENTER -> mutableChKey = '\n'
      }
    }
    val commandBuilder = keyState.commandBuilder
    if (mutableChKey.code != 0) {
      LOG.trace("Add character argument to the current command")
      // Create the character argument, add it to the current command, and signal we are ready to process the command
      commandBuilder.completeCommandPart(Argument(mutableChKey))
    } else {
      LOG.trace("This is not a valid character argument. Set command state to BAD_COMMAND")
      // Oops - this isn't a valid character argument
      commandBuilder.commandState = CurrentCommandState.BAD_COMMAND
    }
    editor.isReplaceCharacter = false
  }

  private fun handleDigraph(
    editor: VimEditor,
    key: KeyStroke,
    keyState: KeyHandlerState,
    context: ExecutionContext,
  ): Boolean {
    LOG.debug("Handling digraph")
    // Support starting a digraph/literal sequence if the operator accepts one as an argument, e.g. 'r' or 'f'.
    // Normally, we start the sequence (in Insert or CmdLine mode) through a VimAction that can be mapped. Our
    // VimActions don't work as arguments for operators, so we have to special case here. Helpfully, Vim appears to
    // hardcode the shortcuts, and doesn't support mapping, so everything works nicely.
    val commandBuilder = keyState.commandBuilder
    val digraphSequence = keyState.digraphSequence
    if (commandBuilder.expectedArgumentType == Argument.Type.DIGRAPH) {
      LOG.trace("Expected argument is digraph")
      if (digraphSequence.isDigraphStart(key)) {
        digraphSequence.startDigraphSequence()
        commandBuilder.addKey(key)
        return true
      }
      if (digraphSequence.isLiteralStart(key)) {
        digraphSequence.startLiteralSequence()
        commandBuilder.addKey(key)
        return true
      }
    }
    val res = digraphSequence.processKey(key, editor)
    if (injector.exEntryPanel.isActive()) {
      when (res.result) {
        DigraphResult.RES_HANDLED -> setPromptCharacterEx(if (commandBuilder.isPuttingLiteral()) '^' else key.keyChar)
        DigraphResult.RES_DONE, DigraphResult.RES_BAD -> if (key.keyCode == KeyEvent.VK_C && key.modifiers and InputEvent.CTRL_DOWN_MASK != 0) {
          return false
        } else {
          injector.exEntryPanel.clearCurrentAction()
        }
      }
    }
    when (res.result) {
      DigraphResult.RES_HANDLED -> {
        commandBuilder.addKey(key)
        return true
      }
      DigraphResult.RES_DONE -> {
        if (commandBuilder.expectedArgumentType === Argument.Type.DIGRAPH) {
          commandBuilder.fallbackToCharacterArgument()
        }
        val stroke = res.stroke ?: return false
        commandBuilder.addKey(key)
        handleKey(editor, stroke, context, keyState)
        return true
      }
      DigraphResult.RES_BAD -> {
        // BAD is an error. We were expecting a valid character, and we didn't get it.
        if (commandBuilder.expectedArgumentType != null) {
          commandBuilder.commandState = CurrentCommandState.BAD_COMMAND
        }
        return true
      }
      DigraphResult.RES_UNHANDLED -> {
        // UNHANDLED means the keystroke made no sense in the context of a digraph, but isn't an error in the current
        // state. E.g. waiting for {char} <BS> {char}. Let the key handler have a go at it.
        if (commandBuilder.expectedArgumentType === Argument.Type.DIGRAPH) {
          commandBuilder.fallbackToCharacterArgument()
          handleKey(editor, key, context, keyState)
          return true
        }
        return false
      }
    }
    return false
  }

  private fun executeCommand(
    editor: VimEditor,
    context: ExecutionContext,
    editorState: VimStateMachine,
    keyState: KeyHandlerState,
  ) {
    LOG.trace("Command execution")
    val command = keyState.commandBuilder.buildCommand()
    val operatorArguments = OperatorArguments(
      editor.mode is Mode.OP_PENDING,
      command.rawCount,
      editorState.mode,
    )

    // If we were in "operator pending" mode, reset back to normal mode.
    editor.resetOpPending()

    // Save off the command we are about to execute
    editorState.executingCommand = command
    val type = command.type
    if (type.isWrite) {
      if (!editor.isWritable()) {
        injector.messages.indicateError()
        reset(keyState, editorState.mode)
        LOG.warn("File is not writable")
        return
      }
    }
    if (injector.application.isMainThread()) {
      val action: Runnable = ActionRunner(editor, context, command, keyState, operatorArguments)
      val cmdAction = command.action
      val name = cmdAction.id
      if (type.isWrite) {
        injector.application.runWriteCommand(editor, name, action, action)
      } else if (type.isRead) {
        injector.application.runReadCommand(editor, name, action, action)
      } else {
        injector.actionExecutor.executeCommand(editor, action, name, action)
      }
    }
  }

  private fun handleCommandNode(
    editor: VimEditor,
    context: ExecutionContext,
    key: KeyStroke,
    node: CommandNode<LazyVimCommand>,
    keyState: KeyHandlerState,
    editorState: VimStateMachine,
  ) {
    LOG.trace("Handle command node")
    // The user entered a valid command. Create the command and add it to the stack.
    val action = node.actionHolder.instance
    val commandBuilder = keyState.commandBuilder
    val expectedArgumentType = commandBuilder.expectedArgumentType
    commandBuilder.pushCommandPart(action)
    if (!checkArgumentCompatibility(expectedArgumentType, action)) {
      LOG.trace("Return from command node handling")
      commandBuilder.commandState = CurrentCommandState.BAD_COMMAND
      return
    }
    if (action.argumentType == null || stopMacroRecord(node)) {
      LOG.trace("Set command state to READY")
      commandBuilder.commandState = CurrentCommandState.READY
    } else {
      LOG.trace("Set waiting for the argument")
      val argumentType = action.argumentType
      startWaitingForArgument(editor, context, key.keyChar, action, argumentType!!, keyState, editorState)
      partialReset(keyState, editorState.mode)
    }

    // TODO In the name of God, get rid of EX_STRING, FLAG_COMPLETE_EX and all the related staff
    if (expectedArgumentType === Argument.Type.EX_STRING && action.flags.contains(CommandFlags.FLAG_COMPLETE_EX)) {
      /* The only action that implements FLAG_COMPLETE_EX is ProcessExEntryAction.
   * When pressing ':', ExEntryAction is chosen as the command. Since it expects no arguments, it is invoked and
     calls ProcessGroup#startExCommand, pushes CMD_LINE mode, and the action is popped. The ex handler will push
     the final <CR> through handleKey, which chooses ProcessExEntryAction. Because we're not expecting EX_STRING,
     this branch does NOT fire, and ProcessExEntryAction handles the ex cmd line entry.
   * When pressing '/' or '?', SearchEntry(Fwd|Rev)Action is chosen as the command. This expects an argument of
     EX_STRING, so startWaitingForArgument calls ProcessGroup#startSearchCommand. The ex handler pushes the final
     <CR> through handleKey, which chooses ProcessExEntryAction, and we hit this branch. We don't invoke
     ProcessExEntryAction, but pop it, set the search text as an argument on SearchEntry(Fwd|Rev)Action and invoke
     that instead.
   * When using '/' or '?' as part of a motion (e.g. "d/foo"), the above happens again, and all is good. Because
     the text has been applied as an argument on the last command, '.' will correctly repeat it.

   It's hard to see how to improve this. Removing EX_STRING means starting ex input has to happen in ExEntryAction
   and SearchEntry(Fwd|Rev)Action, and the ex command invoked in ProcessExEntryAction, but that breaks any initial
   operator, which would be invoked first (e.g. 'd' in "d/foo").
*/
      LOG.trace("Processing ex_string")
      val text = injector.processGroup.endSearchCommand()
      commandBuilder.popCommandPart() // Pop ProcessExEntryAction
      commandBuilder.completeCommandPart(Argument(text)) // Set search text on SearchEntry(Fwd|Rev)Action
      editor.mode = editorState.mode.returnTo()
    }
  }

  private fun stopMacroRecord(node: CommandNode<LazyVimCommand>): Boolean {
    // TODO
//    return editorState.isRecording && node.actionHolder.getInstance() is ToggleRecordingAction
    return injector.registerGroup.isRecording && node.actionHolder.instance.id == "VimToggleRecordingAction"
  }

  private fun startWaitingForArgument(
    editor: VimEditor,
    context: ExecutionContext,
    key: Char,
    action: EditorActionHandlerBase,
    argument: Argument.Type,
    keyState: KeyHandlerState,
    editorState: VimStateMachine,
  ) {
    val commandBuilder = keyState.commandBuilder
    when (argument) {
      Argument.Type.MOTION -> {
        if (editorState.isDotRepeatInProgress && argumentCaptured != null) {
          commandBuilder.completeCommandPart(argumentCaptured!!)
        }
        editor.mode = Mode.OP_PENDING(editorState.mode.returnTo)
      }

      Argument.Type.DIGRAPH -> // Command actions represent the completion of a command. Showcmd relies on this - if the action represents a
        // part of a command, the showcmd output is reset part way through. This means we need to special case entering
        // digraph/literal input mode. We have an action that takes a digraph as an argument, and pushes it back through
        // the key handler when it's complete.

        // TODO
//        if (action is InsertCompletedDigraphAction) {
        if (action.id == "VimInsertCompletedDigraphAction") {
          keyState.digraphSequence.startDigraphSequence()
          setPromptCharacterEx('?')
        } else if (action.id == "VimInsertCompletedLiteralAction") {
          keyState.digraphSequence.startLiteralSequence()
          setPromptCharacterEx('^')
        }

      Argument.Type.EX_STRING -> {
        // The current Command expects an EX_STRING argument. E.g. SearchEntry(Fwd|Rev)Action. This won't execute until
        // state hits READY. Start the ex input field, push CMD_LINE mode and wait for the argument.
        injector.processGroup.startSearchCommand(editor, context, commandBuilder.count, key)
        commandBuilder.commandState = CurrentCommandState.NEW_COMMAND
        val currentMode = editorState.mode
        check(currentMode is ReturnableFromCmd) { "Cannot enable command line mode $currentMode" }
        editor.mode = Mode.CMD_LINE(currentMode)
      }

      else -> Unit
    }

    // Another special case. Force a mode change to update the caret shape
    // This was a typed solution
    // if (action is ChangeCharacterAction || action is ChangeVisualCharacterAction)
    if (action.id == "VimChangeCharacterAction" || action.id == "VimChangeVisualCharacterAction") {
      editor.isReplaceCharacter = true
    }
  }

  private fun checkArgumentCompatibility(
    expectedArgumentType: Argument.Type?,
    action: EditorActionHandlerBase,
  ): Boolean {
    return !(expectedArgumentType === Argument.Type.MOTION && action.type !== Command.Type.MOTION)
  }

  /**
   * Partially resets the state of this handler. Resets the command count, clears the key list, resets the key tree
   * node to the root for the current mode we are in.
   *
   * @param editor The editor to reset.
   */
  public fun partialReset(editor: VimEditor) {
    partialReset(keyHandlerState, editor.mode)
  }

  // TODO replace with com.maddyhome.idea.vim.state.KeyHandlerState#partialReset
  private fun partialReset(keyState: KeyHandlerState, mode: Mode) {
    keyState.mappingState.resetMappingSequence()
    keyState.commandBuilder.resetInProgressCommandPart(getKeyRoot(mode.toMappingMode()))
  }

  /**
   * Resets the state of this handler. Does a partial reset then resets the mode, the command, and the argument.
   *
   * @param editor The editor to reset.
   */
  // TODO replace with com.maddyhome.idea.vim.state.KeyHandlerState#reset
  public fun reset(editor: VimEditor) {
    partialReset(keyHandlerState, editor.mode)
    keyHandlerState.commandBuilder.resetAll(getKeyRoot(editor.mode.toMappingMode()))
  }

  // TODO replace with com.maddyhome.idea.vim.state.KeyHandlerState#reset
  public fun reset(keyState: KeyHandlerState, mode: Mode) {
    partialReset(keyState, mode)
    keyState.commandBuilder.resetAll(getKeyRoot(mode.toMappingMode()))
  }

  private fun getKeyRoot(mappingMode: MappingMode): CommandPartNode<LazyVimCommand> {
    return injector.keyGroup.getKeyRoot(mappingMode)
  }

  public fun updateState(keyState: KeyHandlerState) {
    this.keyHandlerState = keyState
  }

  /**
   * Completely resets the state of this handler. Resets the command mode to normal, resets, and clears the selected
   * register.
   *
   * @param editor The editor to reset.
   */
  public fun fullReset(editor: VimEditor) {
    injector.messages.clearError()
    editor.resetState()
    reset(keyHandlerState, editor.mode)
    injector.registerGroupIfCreated?.resetRegister()
    editor.removeSelection()
  }

  private fun setPromptCharacterEx(promptCharacter: Char) {
    val exEntryPanel = injector.exEntryPanel
    if (exEntryPanel.isActive()) {
      exEntryPanel.setCurrentActionPromptCharacter(promptCharacter)
    }
  }

  /**
   * This was used as an experiment to execute actions as a runnable.
   */
  internal class ActionRunner(
    val editor: VimEditor,
    val context: ExecutionContext,
    val cmd: Command,
    val keyState: KeyHandlerState,
    val operatorArguments: OperatorArguments,
  ) : Runnable {
    override fun run() {
      val editorState = VimStateMachine.getInstance(editor)
      keyState.commandBuilder.commandState = CurrentCommandState.NEW_COMMAND
      val register = cmd.register
      if (register != null) {
        injector.registerGroup.selectRegister(register)
      }
      injector.actionExecutor.executeVimAction(editor, cmd.action, context, operatorArguments)
      if (editorState.mode is Mode.INSERT || editorState.mode is Mode.REPLACE) {
        injector.changeGroup.processCommand(editor, cmd)
      }

      // Now the command has been executed let's clean up a few things.

      // By default, the "empty" register is used by all commands, so we want to reset whatever the last register
      // selected by the user was to the empty register
      injector.registerGroup.resetRegister()

      // If, at this point, we are not in insert, replace, or visual modes, we need to restore the previous
      // mode we were in. This handles commands in those modes that temporarily allow us to execute normal
      // mode commands. An exception is if this command should leave us in the temporary mode such as
      // "select register"
      val myMode = editorState.mode
      val returnTo = myMode.returnTo
      if (myMode is Mode.NORMAL && returnTo != null && !cmd.flags.contains(CommandFlags.FLAG_EXPECT_MORE)) {
        when (returnTo) {
          ReturnTo.INSERT -> {
            editor.mode = Mode.INSERT
          }

          ReturnTo.REPLACE -> {
            editor.mode = Mode.REPLACE
          }
        }
      }
      if (keyState.commandBuilder.isDone()) {
        getInstance().reset(keyState, editorState.mode)
      }
    }
  }

  public companion object {
    private val LOG: VimLogger = vimLogger<KeyHandler>()

    internal fun <T> isPrefix(list1: List<T>, list2: List<T>): Boolean {
      if (list1.size > list2.size) {
        return false
      }
      for (i in list1.indices) {
        if (list1[i] != list2[i]) {
          return false
        }
      }
      return true
    }

    private val instance = KeyHandler()

    @JvmStatic
    public fun getInstance(): KeyHandler = instance
  }
}

/**
 * This class was created to manage Fleet input processing.
 * Fleet needs to synchronously determine if the key will be handled by the plugin or should be passed elsewhere.
 * The key processing itself will be executed asynchronously at a later time.
 */
public sealed interface KeyProcessResult {
  /**
   * Key input that is not recognized by IdeaVim and should be passed to IDE.
   */
  public object Unknown: KeyProcessResult

  /**
   * Key input that is recognized by IdeaVim and can be processed.
   * Key handling is a two-step process:
   * 1. Determine if the key should be processed and how (is it a command, mapping, or something else).
   * 2. Execute the recognized command.
   * This class should be returned after the first step is complete.
   * It will continue the key handling and finish the process.
   */
  public class Processable(
    private val originalState: KeyHandlerState,
    private val preProcessState: KeyHandlerState,
    private val processing: (
      key: KeyStroke,
      keyState: KeyHandlerState,
      editor: VimEditor,
      context: ExecutionContext,
      allowKeyMappings: Boolean,
      mappingCompleted: Boolean,
    ) -> KeyHandlerState
  ): KeyProcessResult {

    public companion object {
      private val logger = vimLogger<KeyProcessResult>()
      private val lock = Object()
    }

    // TODO add concurrency to other places
    public fun processKey(key: KeyStroke, editor: VimEditor, context: ExecutionContext, allowKeyMappings: Boolean, mappingCompleted: Boolean) {
      synchronized(lock) {
        val keyHandler = KeyHandler.getInstance()
        if (keyHandler.keyHandlerState != originalState) {
          logger.warn("Unexpected editor state. Aborting command execution.")
        }
        val newState = processing(key, preProcessState, editor, context, allowKeyMappings, mappingCompleted)
        keyHandler.updateState(newState)
      }
    }
  }
}
