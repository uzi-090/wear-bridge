import XCTest
@testable import PairingPOC

final class ContactsIntegrityTests: XCTestCase {
    private let a = SyncContact(id: "a", name: "Alpha", phones: ["+15550100001"], emails: [], favorite: false)
    private let b = SyncContact(id: "b", name: "Beta", phones: [], emails: ["test@example.com"], favorite: true)
    func testCanonicalBookDigestMatchesIndependentWireFixture() {
        let expected = "b7268b291a58e9ebfb1a9288eaf044ca20a023051525be9b98f3da615ad1c32e"
        XCTAssertEqual(ContactsCodec.bookDigest([b, a]).map { String(format: "%02x", $0) }.joined(), expected)
    }
    func testBeginCarriesBothOperationCountsAndTransactionIdentity() {
        XCTAssertEqual(ContactsCodec.begin(full: true, count: 1, deletes: 2, transaction: 0x01020304),
                       [1, 0, 1, 0, 2, 1, 2, 3, 4])
    }
    func testEndCarriesGenerationIdentityAndCompleteBookDigest() {
        let end = ContactsCodec.end(generation: 7, transaction: 9, contacts: [a, b])
        XCTAssertEqual(end.count, 40)
        XCTAssertEqual(Array(end.prefix(8)), [0, 0, 0, 7, 0, 0, 0, 9])
        XCTAssertEqual(Array(end.dropFirst(8)), ContactsCodec.bookDigest([a, b]))
    }
}
