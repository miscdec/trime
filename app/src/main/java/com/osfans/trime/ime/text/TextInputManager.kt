// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.text

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.core.Rime
import com.osfans.trime.core.RimeNotification
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.AppPrefs
import com.osfans.trime.data.schema.SchemaManager
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.EventManager
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.Speech
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.dialog.AvailableSchemaPickerDialog
import com.osfans.trime.ime.dialog.EnabledSchemaPickerDialog
import com.osfans.trime.ime.enums.Keycode
import com.osfans.trime.ime.enums.Keycode.Companion.toStdKeyEvent
import com.osfans.trime.ime.keyboard.Event
import com.osfans.trime.ime.keyboard.InputFeedbackManager
import com.osfans.trime.ime.keyboard.Keyboard
import com.osfans.trime.ime.keyboard.KeyboardSwitcher
import com.osfans.trime.ime.keyboard.KeyboardView
import com.osfans.trime.ime.symbol.SymbolBoardType
import com.osfans.trime.ui.main.settings.ColorPickerDialog
import com.osfans.trime.ui.main.settings.KeySoundEffectPickerDialog
import com.osfans.trime.ui.main.settings.ThemePickerDialog
import com.osfans.trime.util.ShortcutUtils
import com.osfans.trime.util.startsWithAsciiChar
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import splitties.systemservices.inputMethodManager
import splitties.views.dsl.core.withTheme
import timber.log.Timber
import java.util.Locale

/**
 * TextInputManager is responsible for managing everything which is related to text input. All of
 * the following count as text input: character, numeric (+advanced), phone and symbol layouts.
 *
 * All of the UI for the different keyboard layouts are kept under the same container element and
 * are separated from media-related UI. The core [TrimeInputMethodService] will pass any event defined in
 * [TrimeInputMethodService.EventListener] through to this class.
 *
 * TextInputManager is also the hub in the communication between the system, the active editor
 * instance and the CandidateView.
 */
class TextInputManager(
    private val trime: TrimeInputMethodService,
    private val rime: RimeSession,
) : TrimeInputMethodService.EventListener,
    KeyboardView.OnKeyboardActionListener,
    Candidate.EventListener {
    private val prefs get() = AppPrefs.defaultInstance()
    private var rimeNotificationJob: Job? = null

    val locales = Array(2) { Locale.getDefault() }

    var needSendUpRimeKey: Boolean = false
    var isComposable: Boolean = false

    private var shouldUpdateRimeOption
        get() = trime.shouldUpdateRimeOption
        set(value) {
            trime.shouldUpdateRimeOption = value
        }
    private val shouldResetAsciiMode get() = trime.shouldResetAsciiMode

    // TODO: move things using this context to InputView scope.
    private val themedContext = trime.withTheme(android.R.style.Theme_DeviceDefault_Settings)

    companion object {
        /** Delimiter regex for key property group, their format like `{property_1: value_1, property_2: value_2}` */
        private val DELIMITER_PROPERTY_GROUP = """^(\{[^{}]+\}).*$""".toRegex()

        /** Delimiter regex for property key tag, its format like `Escape: ` following a property group like above */
        private val DELIMITER_PROPERTY_KEY = """^((\{Escape\})?[^{}]+).*$""".toRegex()

        /** Delimiter regex to split language/locale tags. */
        private val DELIMITER_SPLITTER = """[-_]""".toRegex()
        private var instance: TextInputManager? = null

        fun instanceOrNull(): TextInputManager? {
            return instance
        }
    }

    init {
        instance = this
        trime.addEventListener(this)
    }

    /**
     * Non-UI-related setup + preloading of all required computed layouts (asynchronous in the
     * background).
     */
    override fun onCreate() {
        super.onCreate()
        rimeNotificationJob =
            rime.run { notificationFlow }
                .onEach(::handleRimeNotification)
                .launchIn(trime.lifecycleScope)

        val theme = ThemeManager.activeTheme
        val defaultLocale = theme.generalStyle.locale.split(DELIMITER_SPLITTER)
        locales[0] =
            when (defaultLocale.size) {
                3 -> Locale(defaultLocale[0], defaultLocale[1], defaultLocale[2])
                2 -> Locale(defaultLocale[0], defaultLocale[1])
                else -> Locale.getDefault()
            }

        val latinLocale = theme.generalStyle.latinLocale.split(DELIMITER_SPLITTER)
        locales[1] =
            when (latinLocale.size) {
                3 -> Locale(latinLocale[0], latinLocale[1], latinLocale[2])
                2 -> Locale(latinLocale[0], latinLocale[1])
                else -> Locale.US
            }
        // preload all required parameters
        trime.loadConfig()
    }

    /**
     * Cancels all coroutines and cleans up.
     */
    override fun onDestroy() {
        rimeNotificationJob?.cancel()
        rimeNotificationJob = null
        instance = null
    }

    override fun onStartInputView(
        info: EditorInfo,
        restarting: Boolean,
    ) {
        super.onStartInputView(info, restarting)
        trime.selectLiquidKeyboard(-1)
        if (restarting) {
            trime.performEscape()
        }
        isComposable = false
        var forceAsciiMode = false
        val keyboardType =
            when (info.imeOptions and EditorInfo.IME_FLAG_FORCE_ASCII) {
                EditorInfo.IME_FLAG_FORCE_ASCII -> {
                    forceAsciiMode = true
                    ".ascii"
                }
                else -> {
                    val inputAttrsRaw = info.inputType
                    isComposable = inputAttrsRaw > 0
                    when (inputAttrsRaw and InputType.TYPE_MASK_CLASS) {
                        InputType.TYPE_CLASS_NUMBER,
                        InputType.TYPE_CLASS_PHONE,
                        InputType.TYPE_CLASS_DATETIME,
                        -> {
                            forceAsciiMode = true
                            "number"
                        }
                        InputType.TYPE_CLASS_TEXT -> {
                            when (inputAttrsRaw and InputType.TYPE_MASK_VARIATION) {
                                InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE -> {
                                    null
                                }
                                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                                -> {
                                    Timber.i(
                                        "EditorInfo: " +
                                            " inputAttrsRaw" + inputAttrsRaw +
                                            "; InputType" + (inputAttrsRaw and InputType.TYPE_MASK_VARIATION),
                                    )
                                    forceAsciiMode = true
                                    ".ascii"
                                }
                                else -> null.also { isComposable = true }
                            }
                        }
                        else -> {
                            if (inputAttrsRaw <= 0) return
                            null
                        }
                    }
                }
            }

        // Select a keyboard based on the input type of the editing field.
        KeyboardSwitcher.switchKeyboard(keyboardType)

        // style/reset_ascii_mode指定了弹出键盘时是否重置ASCII状态。
        // 键盘的reset_ascii_mode指定了重置时是否重置到keyboard的ascii_mode描述的状态。
        KeyboardSwitcher.currentKeyboard.let {
            if (forceAsciiMode) {
                if (!Rime.isAsciiMode) Rime.setOption("ascii_mode", true)
                return@let
            }
            if (shouldResetAsciiMode) {
                if (it.resetAsciiMode) {
                    if (Rime.isAsciiMode != it.asciiMode) Rime.setOption("ascii_mode", it.asciiMode)
                } else {
                    if (Rime.isAsciiMode) Rime.setOption("ascii_mode", false)
                }
            }
        }
        isComposable = isComposable && !rime.run { isEmpty() }
        trime.updateComposing()
    }

    private fun handleRimeNotification(notification: RimeNotification<*>) {
        if (notification is RimeNotification.SchemaNotification) {
            SchemaManager.init(notification.value.schemaId)
            Rime.updateStatus()
            trime.recreateInputView()
            trime.inputView?.switchBoard(InputView.Board.Main)
        } else if (notification is RimeNotification.OptionNotification) {
            Rime.updateContext() // 切換中英文、簡繁體時更新候選
            val value = notification.value.value
            when (val option = notification.value.option) {
                "ascii_mode" -> {
                    InputFeedbackManager.ttsLanguage =
                        locales[if (value) 1 else 0]
                    KeyboardSwitcher.currentKeyboard.currentAsciiMode = value
                }
                "_hide_bar",
                "_hide_candidate",
                -> {
                    trime.setCandidatesViewShown(isComposable && !value)
                }
                "_liquid_keyboard" -> trime.selectLiquidKeyboard(0)
                else ->
                    if (option.startsWith("_keyboard_") &&
                        option.length > 10 && value
                    ) {
                        val keyboard = option.substring(10)
                        KeyboardSwitcher.switchKeyboard(keyboard)
                        trime.bindKeyboardToInputView()
                    } else if (option.startsWith("_key_") && option.length > 5 && value) {
                        shouldUpdateRimeOption = false // 防止在 handleRimeNotification 中 setOption
                        val key = option.substring(5)
                        onEvent(EventManager.getEvent(key))
                        shouldUpdateRimeOption = true
                    }
            }
        }
    }

    override fun onPress(keyEventCode: Int) {
        InputFeedbackManager.let {
            it.keyPressVibrate(trime.window.window!!.decorView)
            it.keyPressSound(keyEventCode)
            it.keyPressSpeak(keyEventCode)
        }
    }

    override fun onRelease(keyEventCode: Int) {
        Timber.d(
            "\t<TrimeInput>\tonRelease() needSendUpRimeKey=" + needSendUpRimeKey + ", keyEventcode=" + keyEventCode +
                ", Event.getRimeEvent=" + Event.getRimeEvent(keyEventCode, Rime.META_RELEASE_ON),
        )
        if (needSendUpRimeKey) {
            if (shouldUpdateRimeOption) {
                Rime.setOption("soft_cursors", prefs.keyboard.softCursorEnabled)
                Rime.setOption("_horizontal", ThemeManager.activeTheme.generalStyle.horizontal)
                shouldUpdateRimeOption = false
            }
            // todo 释放按键可能不对
            val event = Event.getRimeEvent(keyEventCode, Rime.META_RELEASE_ON)
            Rime.processKey(event[0], event[1])
            trime.commitRimeText()
        }
        Timber.d("\t<TrimeInput>\tonRelease() finish")
    }

    // KeyboardEvent 处理软键盘事件
    override fun onEvent(event: Event?) {
        event ?: return
        if (event.commit.isNotEmpty()) {
            // Directly commit the text and don't dispatch to Rime
            trime.commitCharSequence(event.commit, true)
            return
        }
        if (event.getText(KeyboardSwitcher.currentKeyboard).isNotEmpty()) {
            onText(event.getText(KeyboardSwitcher.currentKeyboard))
            return
        }
        when (event.code) {
            KeyEvent.KEYCODE_SWITCH_CHARSET -> { // Switch status
                Rime.toggleOption(event.getToggle())
                trime.commitRimeText()
            }
            KeyEvent.KEYCODE_EISU -> { // Switch keyboard
                KeyboardSwitcher.switchKeyboard(event.select)
                /** Set ascii mode according to keyboard's settings, can not place into [Rime.handleRimeNotification] */
                KeyboardSwitcher.currentKeyboard.let {
                    if (Rime.isAsciiMode != it.currentAsciiMode) {
                        Rime.setOption("ascii_mode", it.currentAsciiMode)
                    }
                }
                trime.bindKeyboardToInputView()
                trime.updateComposing()
            }
            KeyEvent.KEYCODE_LANGUAGE_SWITCH -> { // Switch IME
                when {
                    event.select!!.contentEquals(".next") -> {
                        trime.switchToNextIme()
                    }
                    !event.select.isNullOrEmpty() -> {
                        trime.switchToPrevIme()
                    }
                    else -> {
                        inputMethodManager.showInputMethodPicker()
                    }
                }
            }
            KeyEvent.KEYCODE_FUNCTION -> { // Command Express
                // Comments from trime.yaml:
                // %s或者%1$s爲當前字符
                // %2$s爲當前輸入的編碼
                // %3$s爲光標前字符
                // %4$s爲光標前所有字符
                var arg = event.option
                val activeTextRegex = Regex(".*%(\\d*)\\$" + "s.*")
                if (arg.matches(activeTextRegex)) {
                    var activeTextMode =
                        arg.replaceFirst(activeTextRegex, "$1").toDouble().toInt()
                    if (activeTextMode < 1) {
                        activeTextMode = 1
                    }
                    val activeText = trime.getActiveText(activeTextMode)
                    arg =
                        String.format(
                            arg,
                            trime.lastCommittedText,
                            Rime.getRimeRawInput() ?: "",
                            activeText,
                            activeText,
                        )
                }

                when (event.command) {
                    "liquid_keyboard" -> trime.selectLiquidKeyboard(arg)
                    "paste_by_char" -> trime.pasteByChar()
                    "set_color_scheme" -> ColorManager.setColorScheme(arg)
                    else -> {
                        ShortcutUtils.call(trime, event.command, arg)?.let {
                            trime.commitCharSequence(it)
                            trime.updateComposing()
                        }
                    }
                }
            }
            KeyEvent.KEYCODE_VOICE_ASSIST -> Speech(trime).startListening() // Speech Recognition
            KeyEvent.KEYCODE_SETTINGS -> { // Settings
                trime.lifecycleScope.launch {
                    when (event.option) {
                        "theme" -> trime.inputView?.showDialog(ThemePickerDialog.build(trime.lifecycleScope, themedContext))
                        "color" -> trime.inputView?.showDialog(ColorPickerDialog.build(trime.lifecycleScope, themedContext))
                        "schema" ->
                            rime.launchOnReady { api ->
                                trime.lifecycleScope.launch {
                                    trime.inputView?.showDialog(AvailableSchemaPickerDialog.build(api, trime.lifecycleScope, themedContext))
                                }
                            }
                        "sound" -> trime.inputView?.showDialog(KeySoundEffectPickerDialog.build(trime.lifecycleScope, themedContext))
                        else -> ShortcutUtils.launchMainActivity(trime)
                    }
                }
            }
            KeyEvent.KEYCODE_PROG_RED ->
                trime.lifecycleScope.launch {
                    trime.inputView?.showDialog(ColorPickerDialog.build(trime.lifecycleScope, themedContext))
                }
            KeyEvent.KEYCODE_MENU -> {
                rime.launchOnReady { api ->
                    trime.lifecycleScope.launch {
                        trime.inputView?.showDialog(
                            EnabledSchemaPickerDialog.build(api, trime.lifecycleScope, themedContext) {
                                setPositiveButton(R.string.enable_schemata) { _, _ ->
                                    trime.lifecycleScope.launch {
                                        trime.inputView?.showDialog(AvailableSchemaPickerDialog.build(api, trime.lifecycleScope, context))
                                    }
                                }
                                setNegativeButton(R.string.set_ime) { _, _ ->
                                    ShortcutUtils.launchMainActivity(context)
                                }
                            },
                        )
                    }
                }
            }
            else -> {
                if (event.mask == 0 && KeyboardSwitcher.currentKeyboard.isOnlyShiftOn) {
                    if (event.code == KeyEvent.KEYCODE_SPACE && prefs.keyboard.hookShiftSpace) {
                        onKey(event.code, 0)
                        return
                    } else if (event.code >= KeyEvent.KEYCODE_0 && event.code <= KeyEvent.KEYCODE_9 && prefs.keyboard.hookShiftNum) {
                        onKey(event.code, 0)
                        return
                    } else if (prefs.keyboard.hookShiftSymbol) {
                        if (event.code >= KeyEvent.KEYCODE_GRAVE && event.code <= KeyEvent.KEYCODE_SLASH ||
                            event.code == KeyEvent.KEYCODE_COMMA ||
                            event.code == KeyEvent.KEYCODE_PERIOD
                        ) {
                            onKey(event.code, 0)
                            return
                        }
                    }
                }
                if (event.mask == 0) {
                    onKey(event.code, KeyboardSwitcher.currentKeyboard.modifer)
                } else {
                    onKey(event.code, event.mask)
                }
            }
        }
    }

    override fun onKey(
        keyEventCode: Int,
        metaState: Int,
    ) {
        Keyboard.printModifierKeyState(metaState, "keyEventCode=$keyEventCode")

        // 优先由librime处理按键事件
        if (trime.handleKey(keyEventCode, metaState)) return

        needSendUpRimeKey = false

        // 如果没有修饰键，或者只有shift修饰键，针对非Android标准按键，可以直接commit字符
        if ((metaState == KeyEvent.META_SHIFT_ON || metaState == 0) && keyEventCode >= Keycode.A.ordinal) {
            val text = Keycode.getSymbolLabel(Keycode.valueOf(keyEventCode))
            if (text.length == 1) {
                trime.commitCharSequence(text)
                return
            }
        }
        // 小键盘自动增加锁定
        if (keyEventCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyEventCode <= KeyEvent.KEYCODE_NUMPAD_EQUALS) {
            trime.sendDownUpKeyEvent(keyEventCode, metaState or KeyEvent.META_NUM_LOCK_ON)
            return
        }
        // 大写字母和部分符号转换为Shift+Android keyevent
        val event = toStdKeyEvent(keyEventCode, metaState)
        trime.sendDownUpKeyEvent(event[0], event[1])
    }

    override fun onText(text: CharSequence?) {
        text ?: return
        if (!text.startsWithAsciiChar() && Rime.isComposing) {
            Rime.commitComposition()
            trime.commitRimeText()
        }
        var textToParse = text
        while (textToParse!!.isNotEmpty()) {
            var target: String
            val escapeTagMatcher = DELIMITER_PROPERTY_KEY.toPattern().matcher(textToParse)
            val propertyGroupMatcher = DELIMITER_PROPERTY_GROUP.toPattern().matcher(textToParse)
            when {
                escapeTagMatcher.matches() -> {
                    target = escapeTagMatcher.group(1) ?: ""
                    Rime.simulateKeySequence(target)
                    if (!trime.commitRimeText() && !Rime.isComposing) {
                        trime.commitCharSequence(target)
                    }
                    trime.updateComposing()
                }
                propertyGroupMatcher.matches() -> {
                    target = propertyGroupMatcher.group(1) ?: ""
                    onEvent(EventManager.getEvent(target))
                }
                else -> {
                    target = textToParse.substring(0, 1)
                    onEvent(EventManager.getEvent(target))
                }
            }
            textToParse = textToParse.substring(target.length)
        }
        needSendUpRimeKey = false
    }

    /**
     * Commits the pressed candidate and suggest the following words.
     */
    override fun onCandidatePressed(index: Int) {
        onPress(0)
        if (!Rime.isComposing) {
            if (index >= 0) {
                SchemaManager.toggleSwitchOption(index)
                trime.updateComposing()
            }
        } else if (prefs.keyboard.hookCandidate || index > 9) {
            if (Rime.selectCandidate(index)) {
                if (prefs.keyboard.hookCandidateCommit) {
                    // todo 找到切换高亮候选词的API，并把此处改为模拟移动候选后发送空格
                    // 如果使用了lua处理候选上屏，模拟数字键、空格键是非常有必要的
                    trime.commitRimeText()
                } else {
                    trime.commitRimeText()
                }
            }
        } else if (index == 9) {
            trime.handleKey(KeyEvent.KEYCODE_0, 0)
        } else {
            trime.handleKey(KeyEvent.KEYCODE_1 + index, 0)
        }
    }

    override fun onCandidateSymbolPressed(arrow: String) {
        when (arrow) {
            Candidate.PAGE_UP_BUTTON -> onKey(KeyEvent.KEYCODE_PAGE_UP, 0)
            Candidate.PAGE_DOWN_BUTTON -> onKey(KeyEvent.KEYCODE_PAGE_DOWN, 0)
            Candidate.PAGE_EX_BUTTON -> trime.selectLiquidKeyboard(SymbolBoardType.CANDIDATE)
        }
    }

    override fun onCandidateLongClicked(index: Int) {
        Rime.deleteCandidate(index)
        trime.updateComposing()
    }
}
