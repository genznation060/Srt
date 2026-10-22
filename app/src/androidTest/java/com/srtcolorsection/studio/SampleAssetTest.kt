package com.srtcolorsection.studio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.srtcolorsection.studio.domain.SectionEngine
import com.srtcolorsection.studio.domain.SrtParser
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SampleAssetTest {
    @Test
    fun sampleAssetParsesOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val text = context.assets.open("sample_multilingual.srt").bufferedReader().readText()
        val parsed = SrtParser.parse(text)
        assertTrue(parsed.cues.any { it.text.contains("বাংলা") })
        assertTrue(parsed.cues.any { it.text.contains("हिन्दी") })
        assertTrue(SectionEngine.group(parsed.cues).size >= 3)
    }
}
