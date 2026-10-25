package com.lizongying.mytv0.playback

/** Playing/Vout alone also occur for broken or audio-only playback; they are not success. */
class VideoFrameEvidence {
    var videoOutputs: Int = 0
    var textureUpdates: Int = 0
    var decodedVideo: Int = 0
    var displayedPictures: Int = 0

    // Several pieces of evidence agree that actual video frames reached the texture.
    // This intentionally does NOT claim that the TV's displayed pixels are correct.
    fun hasVideoFrames(): Boolean = videoOutputs > 0 && textureUpdates >= 2 &&
        decodedVideo > 0 && displayedPictures > 0
}
