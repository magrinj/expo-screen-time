import DeviceActivity
import FamilyControls
import Foundation
import ManagedSettings
import ScreenTime
import SwiftUI
import Combine

import ExpoModulesCore

public class ExpoScreenTimeModule: Module {
    public func definition() -> ModuleDefinition {
        Name("ExpoScreenTime")
        
        OnCreate {
            if #available(iOS 16.0, *) {
                _ = AuthorizationCenter.shared.authorizationStatus // Trigger the initialization
            }
            
            self.appContext?.permissions?.register([
                ScreenTimePermissionRequester()
            ])
        }
        
        AsyncFunction("getScreenTimePermissionsAsync") { (promise: Promise) in
            appContext?.permissions?.getPermissionUsingRequesterClass(
                ScreenTimePermissionRequester.self,
                resolve: promise.resolver,
                reject: promise.legacyRejecter
            )
        }
        
        AsyncFunction("requestScreenTimePermissionsAsync") { (promise: Promise) in
            appContext?.permissions?.askForPermission(
                usingRequesterClass: ScreenTimePermissionRequester.self,
                resolve: promise.resolver,
                reject: promise.legacyRejecter
            )
        }
        
        View(ExpoScreenTimeSelectAppsView.self) {
            Prop("selection") { (view: ExpoScreenTimeSelectAppsView, selection: String) in
                view.initializeSelection(from: selection)
            }

            Events("onSelectionChange")
        }
    }
}

