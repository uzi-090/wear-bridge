import SwiftUI

@main
struct PairingPOCApp: App {
    @State private var link = WatchLink()

    var body: some Scene {
        WindowGroup {
            ContentView(link: link)
                .preferredColorScheme(.dark)
        }
    }
}
