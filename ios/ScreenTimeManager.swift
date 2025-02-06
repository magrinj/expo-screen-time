import ManagedSettings
import FamilyControls

public class ScreenTimeManager: NSObject {
    lazy var managedSettingsStore = ManagedSettingsStore()
    
    public func denyAppRemoval() {
        managedSettingsStore.application.denyAppRemoval = true
    }
    
    public func allowAppRemoval() {
        managedSettingsStore.application.denyAppRemoval = false
    }
    
    public func denyAppInstallation() {
        managedSettingsStore.application.denyAppInstallation = true
    }
    
    public func allowAppInstallation() {
        managedSettingsStore.application.denyAppInstallation = false
    }
    
    public func blockSelection(_ selection: FamilyActivitySelection) {
        managedSettingsStore.shield.applicationCategories = ShieldSettings.ActivityCategoryPolicy.specific(selection.categoryTokens)
        managedSettingsStore.shield.applications = selection.applicationTokens
        managedSettingsStore.shield.webDomains = selection.webDomainTokens
    }
    
    public func unblockAll() {
        managedSettingsStore.shield.applicationCategories = ShieldSettings.ActivityCategoryPolicy.none
        managedSettingsStore.shield.applications = Set()
        managedSettingsStore.shield.webDomains = Set()
    }
}
