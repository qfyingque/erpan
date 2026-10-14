package com.huigu.phone10.mobile

class SpeechText(private val preserveSpacing: Boolean = false,
                 private val sentenceMode: Boolean = false,
                 private val firstClauseMode: Boolean = false,
                 private val coherentMode: Boolean = false,
                 private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val pending = StringBuilder()
    private var tag: StringBuilder? = null
    private val hidden = mutableListOf<String>()
    private var firstAt: Long? = null
    private var emitted = false
    private var quote: Char? = null

    fun push(delta: String): List<String> {
        val result = mutableListOf<String>()
        for (char in delta) {
            val markup = tag
            if (markup != null) {
                if (markup.length == 1 && !(char.isLetter() || char in "/!?")) {
                    tag = null
                    visible('<', result)
                    visible(char, result)
                    continue
                }
                if (markup.length < 4096) markup.append(char)
                if (quote != null) { if (char == quote) quote = null }
                else if (char == '\'' || char == '"') quote = char
                else if (char == '>') {
                    finishTag(markup.toString())
                    tag = null
                }
            } else if (char == '<') tag = StringBuilder("<")
            else visible(char, result)
        }
        return result
    }

    fun flush(): List<String> = emit().let { if (it.isEmpty()) emptyList() else listOf(it) }

    fun flushReady(): List<String> {
        val deadline = if (coherentMode) { if (emitted) 5000 else 2500 } else if (firstClauseMode || sentenceMode) 1500 else if (emitted) 400 else 150
        if (firstAt?.let { nowMillis() - it >= deadline } != true) return emptyList()
        // A timer or tool pause does not mean the incoming word is complete.
        // Independent local requests must only consume complete clauses here.
        if (firstClauseMode && !coherentMode) {
            val end = lastClauseEnd()
            return if (end > 0) listOfNotNull(emit(end).takeIf { it.isNotEmpty() }) else emptyList()
        }
        return flush()
    }

    private fun lastClauseEnd(): Int = pending.indexOfLast { it in "，。！？；：,.!?;:\n" } + 1

    private fun visible(char: Char, output: MutableList<String>) {
        if (hidden.isNotEmpty()) return
        if (firstAt == null && !char.isWhitespace()) firstAt = nowMillis()
        pending.append(char)
        val boundary = if (coherentMode) {
            val points = pending.codePointCount(0, pending.length)
            // First complete sentence, then longer contextual groups. Keep the punctuation.
            (char == '\n' && points >= 24) ||
                (char in "。！？.!?" && points >= if (emitted) 160 else 24) ||
                (char in "，；：,;:" && points >= 240) ||
                (points >= 300 && !char.isHighSurrogate())
        } else if (firstClauseMode) {
            // First punctuation wins. Later clauses are grouped, but reaching
            // the old 240-character target must not cut a word in half.
            (char in "，。！？；：,.!?;:\n" && (!emitted || pending.length >= 100)) ||
                (pending.codePointCount(0, pending.length) >= 600 && !char.isHighSurrogate())
        } else if (sentenceMode) {
            // Keep tiny introductions attached to the following phrase, and let
            // complete clauses provide context to independent HTTP synthesis.
            (char in "。！？.!?\n" && pending.length >= 8) ||
                (char in "，；：,;:" && pending.length >= 24) || pending.length >= 100
        } else char in "，。！？；：,.!?;:\n" || pending.length >= 180
        if (boundary) {
            val end = if (firstClauseMode && !coherentMode) lastClauseEnd().takeIf { it > 0 } ?: pending.length
                else pending.length
            emit(end).takeIf { it.isNotEmpty() }?.let(output::add)
        }
    }

    private fun emit(end: Int = pending.length): String {
        // A streamed delta may stop between an emoji's UTF-16 code units.
        val count = if ((firstClauseMode || coherentMode) && end > 0 && pending[end - 1].isHighSurrogate())
            end - 1 else end
        val cleaned = pending.substring(0, count).replace(Regex("[`*_#]"), "")
            .replace(Regex("\\s+"), " ")
        val text = if (preserveSpacing && cleaned.isNotBlank()) cleaned else cleaned.trim()
        pending.delete(0, count)
        firstAt = if (pending.isEmpty()) null else if (firstClauseMode && !coherentMode) firstAt else nowMillis()
        if (text.isNotEmpty()) emitted = true
        return text
    }

    private fun finishTag(raw: String) {
        val match = Regex("^<\\s*(/?)\\s*([A-Za-z][A-Za-z0-9_:-]*)").find(raw) ?: return
        val name = match.groupValues[2].lowercase()
        val isHidden = name in setOf("think", "thinking", "analysis", "reasoning", "tool", "tools", "function", "status") ||
            name.startsWith("tool_") || name.startsWith("function_")
        if (match.groupValues[1] == "/") {
            val index = hidden.lastIndexOf(name)
            if (index >= 0) while (hidden.size > index) hidden.removeAt(hidden.lastIndex)
        } else if (isHidden && !raw.trimEnd().endsWith("/>")) hidden.add(name)
    }
}
