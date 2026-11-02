package expo.modules.screentime

import android.content.Context
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.views.ExpoView

// Empty placeholder registered under the iOS view name (see
// ExpoScreenTimeModule). It renders nothing until stage 4 brings the Android
// report renderer. The picker and the token label have their own files.

class ExpoScreenTimeReportView(context: Context, appContext: AppContext) : ExpoView(context, appContext)
