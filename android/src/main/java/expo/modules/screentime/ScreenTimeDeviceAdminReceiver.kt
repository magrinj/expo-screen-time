package expo.modules.screentime

import android.app.admin.DeviceAdminReceiver

/**
 * A device admin with no policy (`res/xml/expo_st_device_admin.xml`): while
 * active, Settings disables "Force stop" and the app cannot be uninstalled
 * until the admin is deactivated. Activated by the `deviceAdmin` permission
 * step. Deactivations become enforcement gaps in step 6.
 */
class ScreenTimeDeviceAdminReceiver : DeviceAdminReceiver()
