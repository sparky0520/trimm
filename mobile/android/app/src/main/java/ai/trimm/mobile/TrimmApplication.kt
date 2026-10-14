package ai.trimm.mobile

import android.app.Application

import ai.trimm.mobile.billing.BillingManager

class TrimmApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        BillingManager.configure(this)
    }
}
