package com.srtcolorsection.studio

import android.app.Application
import com.srtcolorsection.studio.export.DiagLog
import com.srtcolorsection.studio.export.ExportRecovery

class SrtStudioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val interrupted = ExportRecovery.cleanup(this)
        if (interrupted != null) DiagLog.add(interrupted)
    }
}
