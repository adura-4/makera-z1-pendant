import UIKit
import SwiftUI
import Shared

/// Hosts the shared Compose UI (the same screens as the Android app).
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        // Full screen: the Compose UI paints its own background and handles the safe areas.
        ComposeView().ignoresSafeArea()
    }
}
