import DeviceActivity
import FamilyControls
import Foundation
import ManagedSettings
import ScreenTime
import SwiftUI
import Combine
import ExpoModulesCore

struct ActivityToken: ExpoModulesCore.Record {
    @Field
    var data: String?
}

public class ExpoScreenTimeModule: Module {
    public let screenTimeManager = ScreenTimeManager()

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
        
        Function("denyAppRemoval") {
            screenTimeManager.denyAppRemoval()
        }
        
        Function("allowAppRemoval") {
            screenTimeManager.allowAppRemoval()
        }
        
        Function("denyAppInstallation") {
            screenTimeManager.denyAppInstallation()
        }
        
        Function("allowAppInstallation") {
            screenTimeManager.allowAppInstallation()
        }
        
        Function("blockSelection") { (selectionObject: [String: Any]) in
            do {
                let selection = try deserializeFamilyActivitySelection(from: selectionObject)

                screenTimeManager.blockSelection(selection)
            } catch {
                print("Deserialization error: \(error)")
            }
        }
        
        Function("unblockAll") {
            screenTimeManager.unblockAll()
        }
        
        View(ExpoScreenTimeActivityPickerView.self) {
            Prop("selection") { (view: ExpoScreenTimeActivityPickerView, selectionObject: [String: Any]) in
                view.initializeSelection(from: selectionObject)
            }
            
            Prop("headerText") { (view: ExpoScreenTimeActivityPickerView, headerText: String?) in
                view.setHeaderText(headerText)
            }
            
            Prop("footerText") { (view: ExpoScreenTimeActivityPickerView, footerText: String?) in
                view.setFooterText(footerText)
            }
            
            Events("onSelectionChange")
        }
    }
}
