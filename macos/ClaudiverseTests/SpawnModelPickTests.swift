import ClaudiverseCore
import XCTest
@testable import Claudiverse

/// New seat's model: the id sent reads back as the model and 1M picked, and the hints say what the server will do.
final class SpawnModelPickTests: XCTestCase {
    private let opus55 = ModelChoice(label: "Opus 5.5", id: "claude-opus-5-5", oneM: false, since: nil, family: "opus")
    private let opus46 = ModelChoice(label: "Opus 4.6", id: "claude-opus-4-6", oneM: true, since: nil, family: "opus")
    private let haiku55 = ModelChoice(label: "Haiku 5.5", id: "claude-haiku-5-5", oneM: false, since: "2.1.293", family: "haiku")
    private let glm = ModelChoice(label: "GLM-5.3 (Z.ai)", id: "glm-5.3", oneM: false, since: nil, family: "glm")
    private let deepseek = ModelChoice(label: "DeepSeek V4.1 Flash (OpenCode credits)", id: "deepseek-v4.1-flash", oneM: false, since: nil, family: "deepseek")
    private let mistral = ModelChoice(label: "Mistral Medium 3.5", id: "mistral-medium-3.5", oneM: false, since: nil, family: "mistral")

    private var groups: [ModelGroup] {
        ModelCatalog(models: [opus55, haiku55, glm], olderModels: [opus46], aliases: [], fromServer: true).spawnGroups(version: "2.1.300")
    }

    func testTheGroupsAreTheFamiliesWithOlderOnesApart() {
        XCTAssertEqual(groups.map(\.label), ["Opus", "Haiku", "GLM (Z.ai)"])
        XCTAssertEqual(groups[0].models.map(\.id), ["claude-opus-5-5"])
        XCTAssertEqual(groups[0].older.map(\.id), ["claude-opus-4-6"])
    }

    func testTheIdSentReadsBackAsTheModelAndItsOneM() {
        XCTAssertNil(SpawnModelPick.of(sent: nil, in: groups).choice, "the machine's default")
        let older = SpawnModelPick.of(sent: "claude-opus-4-6[1m]", in: groups)
        XCTAssertEqual(older.choice?.id, "claude-opus-4-6", "an older one is found too")
        XCTAssertTrue(older.oneM)
        let zai = SpawnModelPick.of(sent: "glm-5.3[1m]", in: groups)
        XCTAssertEqual(zai.choice?.id, "glm-5.3")
        XCTAssertTrue(zai.oneM)
        XCTAssertFalse(SpawnModelPick.of(sent: "claude-opus-5-5", in: groups).oneM)
        XCTAssertNil(SpawnModelPick.of(sent: "claude-lorem-9[1m]", in: groups).choice, "an id not offered")
        XCTAssertFalse(SpawnModelPick.of(sent: "claude-lorem-9[1m]", in: groups).oneM)
    }

    /// Whatever is picked, the menu shows it back: every model, with 1M asked and not.
    func testEveryPickRoundTrips() {
        for m in groups.flatMap({ $0.models + $0.older }) {
            for oneM in [false, true] {
                let sent = spawnModelId(choice: m, oneM: oneM)
                let back = SpawnModelPick.of(sent: sent, in: groups)
                XCTAssertEqual(back.choice?.id, m.id, sent ?? "nil")
                XCTAssertEqual(back.oneM, oneM && m.spawnOneM, sent ?? "nil")
            }
        }
        XCTAssertEqual(spawnModelId(choice: opus55, oneM: true), "claude-opus-5-5", "already 1M: no suffix")
        XCTAssertEqual(spawnModelId(choice: glm, oneM: true), "glm-5.3[1m]")
    }

    /// A machine whose Claude Code predates a model is not offered it.
    func testOnlyModelsTheMachineKnows() {
        let old = ModelCatalog(models: [opus55, haiku55], olderModels: [], aliases: [], fromServer: true).spawnGroups(version: "2.1.290")
        XCTAssertEqual(old.flatMap { $0.models.map(\.id) }, ["claude-opus-5-5"])
    }

    func testHints() {
        XCTAssertEqual(SpawnModelPick.hint(machine: "lorem", offers: true, choice: glm, mode: "auto"),
                       "The server starts a GLM (Z.ai) seat in accept-edits mode when auto is asked.")
        XCTAssertEqual(SpawnModelPick.hint(machine: "lorem", offers: true, choice: deepseek, mode: "auto"),
                       "The server starts a DeepSeek (OpenCode) seat in accept-edits mode when auto is asked.")
        XCTAssertNil(SpawnModelPick.hint(machine: "lorem", offers: true, choice: deepseek, mode: "acceptEdits"))
        XCTAssertEqual(SpawnModelPick.hint(machine: "lorem", offers: true, choice: mistral, mode: "auto"),
                       "The server starts a Mistral (Vibe Pro) seat in accept-edits mode when auto is asked.")
        XCTAssertNil(SpawnModelPick.hint(machine: "lorem", offers: true, choice: glm, mode: "acceptEdits"))
        XCTAssertNil(SpawnModelPick.hint(machine: "lorem", offers: true, choice: opus55, mode: "auto"))
        XCTAssertNil(SpawnModelPick.hint(machine: "lorem", offers: true, choice: nil, mode: "auto"))
        XCTAssertTrue(SpawnModelPick.hint(machine: "lorem", offers: false, choice: nil, mode: "auto")?.contains("lorem's host agent is older") == true)
    }
}
