import FamilyControls

@available(iOS 15.0, *)
func serializeFamilyActivitySelection(from selection: FamilyActivitySelection) -> [String: Any]? {
    let encoder = JSONEncoder()
    
    if (selection.applicationTokens.isEmpty &&
        selection.categoryTokens.isEmpty &&
        selection.webDomainTokens.isEmpty
    ) {
        return nil
    }
    
    
    do {
        let selectionData = try encoder.encode(selection)
        let selectionJSON = try JSONSerialization.jsonObject(with: selectionData) as? [String: Any]
        
        return selectionJSON
    } catch {
        return nil
    }
}

@available(iOS 15.0, *)
func deserializeFamilyActivitySelection(from selectionObject: [String: Any])
throws -> FamilyActivitySelection {
    let decoder = JSONDecoder()
    
    do {
        let selectionData = try JSONSerialization.data(withJSONObject: selectionObject)
        
        return try decoder.decode(FamilyActivitySelection.self, from: selectionData)
    } catch {
        throw DecodingError.dataCorrupted(
            DecodingError.Context(codingPath: [], debugDescription: "Invalid JSON object")
        )
    }
}
