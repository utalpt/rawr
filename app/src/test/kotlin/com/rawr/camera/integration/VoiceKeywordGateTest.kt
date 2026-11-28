package com.rawr.camera.integration

import kotlin.test.*

class VoiceKeywordGateTest {
    @Test fun aKeywordHeldSteadyFires() {
        val gate = VoiceKeywordGate(stableMs = 300, cooldownMs = 2500)
        assertNull(gate.onHypothesis("cheese", false, 1000))
        assertNull(gate.onHypothesis("cheese", false, 1200))
        assertEquals("cheese", gate.onHypothesis("cheese", false, 1300))
    }

    @Test fun aFinalResultFiresAtOnce() {
        val gate = VoiceKeywordGate()
        assertEquals("capture", gate.onHypothesis("capture", true, 500))
    }

    @Test fun onlyTheWholeHypothesisCounts() {
        val gate = VoiceKeywordGate()
        for (text in listOf("cheese please", "say cheese please", "cheers", "chess", "[unk]", "", "picture")) {
            assertNull(gate.onHypothesis(text, true, 1000L + text.length), text)
        }
    }

    @Test fun aSentenceThatBeginsWithTheKeywordDoesNotFire() {
        val gate = VoiceKeywordGate(stableMs = 300)
        assertNull(gate.onHypothesis("cheese", false, 1000))
        assertNull(gate.onHypothesis("cheese is", false, 1150))   // the next word arrived before the window ended
        assertNull(gate.onHypothesis("cheese is good", false, 1500))
        assertNull(gate.onHypothesis("cheese is good", true, 2000))
    }

    @Test fun theWindowRestartsWhenTheHypothesisChanges() {
        val gate = VoiceKeywordGate(stableMs = 300)
        assertNull(gate.onHypothesis("cheese", false, 1000))
        assertNull(gate.onHypothesis("", false, 1100))
        assertNull(gate.onHypothesis("cheese", false, 1200))       // a new run of the keyword starts here
        assertNull(gate.onHypothesis("cheese", false, 1400))
        assertEquals("cheese", gate.onHypothesis("cheese", false, 1500))
    }

    @Test fun oneWordIsOnePhotoAndTheCooldownEnds() {
        val gate = VoiceKeywordGate(stableMs = 300, cooldownMs = 2500)
        assertEquals("cheese", gate.onHypothesis("cheese", true, 1000))
        assertNull(gate.onHypothesis("cheese", true, 1100))
        assertNull(gate.onHypothesis("cheese", false, 3000))
        assertNull(gate.onHypothesis("cheese", false, 3400))       // inside the cooldown until 3500: the run has not started yet
        assertNull(gate.onHypothesis("cheese", false, 3600))       // the run starts only now
        assertEquals("cheese", gate.onHypothesis("cheese", false, 3900))
    }

    @Test fun caseAndSpacesDoNotMatter() {
        assertEquals("cheese", VoiceKeywordGate().onHypothesis("  Cheese ", true, 0))
    }
}
