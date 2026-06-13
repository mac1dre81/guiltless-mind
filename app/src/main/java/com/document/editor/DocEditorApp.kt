package com.document.editor

import android.app.Application

import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class DocEditorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppDiagnostics.install(this)
        AppDiagnostics.logBreadcrumb(this, "Application created")
    }
}
