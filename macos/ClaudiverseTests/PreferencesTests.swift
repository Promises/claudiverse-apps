@testable import Claudiverse
import XCTest

/// An update to the new bundle id keeps the preferences the old id had, once, and never over newer ones.
final class PreferencesTests: XCTestCase {
    /// Domains in memory: the real preferences daemon writes a file for every domain it ever saw.
    private final class Domains: PreferenceDomains {
        var all: [String: [String: Any]] = [:]
        func persistentDomain(forName domainName: String) -> [String: Any]? { all[domainName] }
        func setPersistentDomain(_ domain: [String: Any], forName domainName: String) { all[domainName] = domain }
    }

    func testTheOldPreferencesAreCopiedOnceAndNeverOverNewOnes() {
        let d = Domains()
        XCTAssertFalse(Preferences.copy(from: "old", to: "new", d), "nothing to copy")
        d.all["old"] = ["serverURL": "http://claudiverse.example:4000"]
        XCTAssertTrue(Preferences.copy(from: "old", to: "new", d))
        XCTAssertEqual(d.all["new"]?["serverURL"] as? String, "http://claudiverse.example:4000")
        d.all["new"] = ["serverURL": "http://claudiverse.example:5000"]
        XCTAssertFalse(Preferences.copy(from: "old", to: "new", d), "the new id has its own already")
        XCTAssertEqual(d.all["new"]?["serverURL"] as? String, "http://claudiverse.example:5000")
    }
}
