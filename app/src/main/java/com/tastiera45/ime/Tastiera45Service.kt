override fun onCommitText(text: String) {
        if (extraPanelContainer.visibility == View.VISIBLE && !isEditingMenu) extraPanelContainer.visibility = View.GONE
        if (!isListeningVoice && swipeBarContainer.visibility == View.VISIBLE) swipeBarContainer.visibility = View.GONE
        OrbBridge.overlayService?.hidePopupsOnTyping()

        val ic = currentInputConnection ?: return
        if (text == " " && ::keyboardView.isInitialized) {
            val before = ic.getTextBeforeCursor(32, 0)?.toString().orEmpty()
            val lastWord = before.substringAfterLast(' ').substringAfterLast('\n')
            if (lastWord.length >= 2) {
                keyboardView.learnWord(lastWord, isIncognito())
            }
        } else if (text.trim().length >= 2 && text.endsWith(" ") && ::keyboardView.isInitialized) {
            keyboardView.learnWord(text.trim(), isIncognito())
        }
        ic.commitText(text, 1)
    }
