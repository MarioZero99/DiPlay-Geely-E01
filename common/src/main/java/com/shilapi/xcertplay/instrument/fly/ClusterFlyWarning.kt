package com.shilapi.xcertplay.instrument.fly

/**
 * The one line the driver sees when a fly was refused.
 *
 * It is shown next to the existing quick bar, over the CarPlay picture, and disappears after two
 * seconds: a dialog would cover the map at the moment the driver is trying to look at it.
 */
object ClusterFlyWarning {

    fun text(block: FlyBlock): String? = when (block) {
        FlyBlock.NOT_NAVIGATING -> "还没进入导航，仪表不会换页"
        FlyBlock.NOT_PROVISIONED -> "车机还没设置完，仪表不会换页"
        FlyBlock.REVERSING -> "倒车或全景时不能飞屏"
        FlyBlock.RADAR -> "雷达报警时不能飞屏"
        FlyBlock.ECALL -> "紧急电话时不能飞屏"
        FlyBlock.TOO_SOON -> "刚才那次还没完成，请稍等"
        FlyBlock.NONE -> null
    }
}
