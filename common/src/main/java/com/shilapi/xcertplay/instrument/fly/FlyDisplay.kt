package com.shilapi.xcertplay.instrument.fly

data class FlyDisplayInfo(
    val id: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val defaultDisplay: Boolean,
)

fun chooseClusterDisplay(displays: List<FlyDisplayInfo>): FlyDisplayInfo? {
    val candidates = displays.filter { !it.defaultDisplay && it.id != 0 }
    return candidates.firstOrNull { it.width == 1920 && it.height == 720 } ?: candidates.firstOrNull()
}

fun displayLines(displays: List<FlyDisplayInfo>, chosen: FlyDisplayInfo?): List<String> {
    val list = displays.joinToString(" ") { display ->
        val suffix = if (display.defaultDisplay) ":default" else ""
        "${display.id}:${display.name}:${display.width}x${display.height}$suffix"
    }
    val choice = if (chosen == null) {
        "fly.display none"
    } else {
        "fly.display use id=${chosen.id} ${chosen.name} ${chosen.width}x${chosen.height}"
    }
    return listOf("fly.display list $list", choice)
}
