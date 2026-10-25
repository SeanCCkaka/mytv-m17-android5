package com.lizongying.mytv0.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFrameEvidenceTest {
    @Test fun anOutputOrSurfaceUpdateDoesNotProveVideoPlayback() {
        val evidence = VideoFrameEvidence()
        evidence.videoOutputs = 1
        evidence.textureUpdates = 10
        assertFalse(evidence.hasVideoFrames())
        evidence.decodedVideo = 3
        assertFalse(evidence.hasVideoFrames())
    }

    @Test fun decodedPicturesWithoutDeliveryAreNotSuccess() {
        val evidence = VideoFrameEvidence()
        evidence.videoOutputs = 1
        evidence.decodedVideo = 20
        evidence.displayedPictures = 20
        assertFalse(evidence.hasVideoFrames())
        evidence.textureUpdates = 1
        assertFalse(evidence.hasVideoFrames())
        evidence.textureUpdates = 2
        assertTrue(evidence.hasVideoFrames())
    }

    @Test fun aFreshChannelHasNoOldFrameEvidence() {
        val prior = VideoFrameEvidence().apply {
            videoOutputs = 1; textureUpdates = 10; decodedVideo = 10; displayedPictures = 10
        }
        assertTrue(prior.hasVideoFrames())
        assertFalse(VideoFrameEvidence().hasVideoFrames())
    }
}
