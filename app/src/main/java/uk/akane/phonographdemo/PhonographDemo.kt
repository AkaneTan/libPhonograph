package uk.akane.phonographdemo

import android.app.Application
import com.google.android.material.color.DynamicColors

class PhonographDemo : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}