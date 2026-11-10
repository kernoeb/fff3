package com.kernoeb.fff3.protocol

/**
 * DOM effects, in the order the strips number them. [EffectInfo.id] is the index 0..28; the frame sends `0x80 + id`.
 * From the protocol notes kept outside this repo.
 */
object DomCatalog {
    val effects: List<EffectInfo> = listOf(
        EffectInfo(0, "Static red", "Static"),
        EffectInfo(1, "Static blue", "Static"),
        EffectInfo(2, "Static green", "Static"),
        EffectInfo(3, "Static cyan", "Static"),
        EffectInfo(4, "Static yellow", "Static"),
        EffectInfo(5, "Static purple", "Static"),
        EffectInfo(6, "Static white", "Static"),
        EffectInfo(7, "Three colour jumping change", "Jump and strobe"),
        EffectInfo(8, "Seven colour jumping change", "Jump and strobe"),
        EffectInfo(9, "Three colour cross fade", "Cross fade"),
        EffectInfo(10, "Seven colour cross fade", "Cross fade"),
        EffectInfo(11, "Red gradual change", "Gradual"),
        EffectInfo(12, "Green gradual change", "Gradual"),
        EffectInfo(13, "Blue gradual change", "Gradual"),
        EffectInfo(14, "Yellow gradual change", "Gradual"),
        EffectInfo(15, "Cyan gradual change", "Gradual"),
        EffectInfo(16, "Purple gradual change", "Gradual"),
        EffectInfo(17, "White gradual change", "Gradual"),
        EffectInfo(18, "Red green cross fade", "Cross fade"),
        EffectInfo(19, "Red blue cross fade", "Cross fade"),
        EffectInfo(20, "Green blue cross fade", "Cross fade"),
        EffectInfo(21, "Seven colour strobe flash", "Jump and strobe"),
        EffectInfo(22, "Red strobe flash", "Jump and strobe"),
        EffectInfo(23, "Green strobe flash", "Jump and strobe"),
        EffectInfo(24, "Blue strobe flash", "Jump and strobe"),
        EffectInfo(25, "Yellow strobe flash", "Jump and strobe"),
        EffectInfo(26, "Cyan strobe flash", "Jump and strobe"),
        EffectInfo(27, "Purple strobe flash", "Jump and strobe"),
        EffectInfo(28, "White strobe flash", "Jump and strobe"),
    )

    val micModes: List<String> = listOf("Classic", "Soft", "Dynamic", "Disco")
}
