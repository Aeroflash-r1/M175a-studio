package com.example

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import com.example.di.appModule

/**
 * Custom Application class for M175a Studio.
 * Responsible for initializing global tools like Koin dependency injection.
 */
class M175aApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        startKoin {
            androidLogger()
            androidContext(this@M175aApplication)
            modules(appModule)
        }
    }
}
