package ai.ctrlb.mobile

import android.app.Application
import com.clerk.api.Clerk

class CtrlBApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.CLERK_PUBLISHABLE_KEY.isNotBlank()) {
            Clerk.initialize(this, publishableKey = BuildConfig.CLERK_PUBLISHABLE_KEY)
        }
    }
}
