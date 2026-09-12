package com.huigu.phone10.mobile

class SpeechText(private val preserveSpacing: Boolean = false,
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
        val deadline = if (emitted) 400 else 150
        return if (firstAt?.let { nowMillis() - it >= deadline } == true) flush() else emptyList()
    }

    private fun visible(char: Char, output: MutableList<String>) {
        if (hidden.isNotEmpty()) return
        if (firstAt == null && !char.isWhitespace()) firstAt = nowMillis()
        pending.append(char)
        if (char in "，。！？；：,.!?;:\n" || pending.length >= 180) emit().takeIf { it.isNotEmpty() }?.let(output::add)
    }

    private fun emit(): String {
        val cleaned = pending.toString().replace(Regex("[`*_#]"), "")
            .replace(Regex("\\s+"), " ")
        val text = if (preserveSpacing && cleaned.isNotBlank()) cleaned else cleaned.trim()
        pending.setLength(0)
        firstAt = null
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
