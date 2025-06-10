// MediaFrame.kt
package com.example.videostream



// 必要なヘルパークラス
enum class FrameType { VIDEO, AUDIO }


data class MediaFrame(
    val type: FrameType,
    val data: ByteArray,
    val timestamp: Long
)
{
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MediaFrame

        if (type != other.type) return false
        if (!data.contentEquals(other.data)) return false
        if (timestamp != other.timestamp) return false

        return true
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}