import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeUIViewController
import com.github.terrakok.wikwok.App
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIViewController

@OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
fun MainViewController(): UIViewController = ComposeUIViewController(
    configure = {
        parallelRendering = true
    }
) {
    App()
}
