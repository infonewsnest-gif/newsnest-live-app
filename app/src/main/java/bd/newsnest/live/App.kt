package bd.newsnest.live

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.os.Bundle
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val f = File(filesDir, "crash.txt")
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { f.writeText(android.util.Log.getStackTraceString(e)) } catch (_: Exception) {}
            old?.uncaughtException(t, e)
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) {
                if (f.exists()) {
                    val msg = f.readText().take(1500)
                    f.delete()
                    AlertDialog.Builder(a).setTitle("আগের ক্র্যাশের কারণ")
                        .setMessage(msg).setPositiveButton("ঠিক আছে", null).show()
                }
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
}
