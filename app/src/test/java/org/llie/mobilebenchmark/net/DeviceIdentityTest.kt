package org.llie.mobilebenchmark.net

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceIdentityTest {

    @Test
    fun fallsBackToTheBuildLabelWhenNoCustomNameIsSet() {
        assertEquals("A142-Pacman", DeviceIdentity.label("A142", "Pacman", ""))
        assertEquals("A142-Pacman", DeviceIdentity.label("A142", "Pacman", "   "))
    }

    @Test
    fun customLabelsAreSanitisedForPathsAndMeta() {
        assertEquals("2a-lab-1", DeviceIdentity.label("A142", "Pacman", "2a lab #1"))
        assertEquals("lab_a", DeviceIdentity.label("A142", "Pacman", "  lab_a  "))
        assertEquals("M2010J19SG", DeviceIdentity.label("M2010J19SG", "lime", " M2010J19SG "))
    }

    @Test
    fun aLabelOfOnlyPunctuationFallsBackToTheBuildLabel() {
        assertEquals("A142-Pacman", DeviceIdentity.label("A142", "Pacman", "###"))
    }
}
