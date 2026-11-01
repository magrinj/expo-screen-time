package expo.modules.screentime

import android.content.Context
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.views.ExpoView

// Empty placeholders registered under the iOS view names (see
// ExpoScreenTimeModule). They render nothing until stage 3 brings the Android
// picker, labels and report renderer.

class ExpoScreenTimeActivityPickerView(context: Context, appContext: AppContext) : ExpoView(context, appContext)

class ExpoScreenTimeReportView(context: Context, appContext: AppContext) : ExpoView(context, appContext)

class ExpoScreenTimeTokenLabelView(context: Context, appContext: AppContext) : ExpoView(context, appContext)
