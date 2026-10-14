package com.huigu.phone10.mobile

import kotlinx.coroutines.channels.ReceiveChannel

/** Immutable synthesis policy for one message. Recognition remains owned by the call. */
class ReplyVoice(
    val speak: suspend (String) -> Unit,
    val streamSpeak: (suspend (ReceiveChannel<String>) -> Unit)?,
    val sentenceTts: Boolean,
    val wholeReplyTts: Boolean,
    val firstClauseTts: Boolean,
    val coherentTts: Boolean,
)
