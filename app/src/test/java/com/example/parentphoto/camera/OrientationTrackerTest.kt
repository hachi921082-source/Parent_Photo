package com.example.parentphoto.camera

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

class OrientationTrackerTest {
    @Test
    fun mapsDegreesToSurfaceRotation() {
        assertEquals(Surface.ROTATION_0, OrientationTracker.rotationFor(0))
        assertEquals(Surface.ROTATION_0, OrientationTracker.rotationFor(44))
        assertEquals(Surface.ROTATION_0, OrientationTracker.rotationFor(315))
        assertEquals(Surface.ROTATION_0, OrientationTracker.rotationFor(359))
        assertEquals(Surface.ROTATION_270, OrientationTracker.rotationFor(45))
        assertEquals(Surface.ROTATION_270, OrientationTracker.rotationFor(90))
        assertEquals(Surface.ROTATION_180, OrientationTracker.rotationFor(135))
        assertEquals(Surface.ROTATION_180, OrientationTracker.rotationFor(180))
        assertEquals(Surface.ROTATION_90, OrientationTracker.rotationFor(225))
        assertEquals(Surface.ROTATION_90, OrientationTracker.rotationFor(270))
    }
}
