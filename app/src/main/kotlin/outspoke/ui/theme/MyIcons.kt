package dev.brgr.outspoke.ui.theme

import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.DefaultFillType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object MyIcons {

    val MicOff: ImageVector
        get() {
            if (_micOff != null) {
                return _micOff!!
            }
            _micOff = materialIcon(name = "Filled.MicOff") {
                materialPath {
                    moveTo(19.0f, 11.0f)
                    horizontalLineToRelative(-1.7f)
                    curveToRelative(0.0f, 0.74f, -0.16f, 1.43f, -0.43f, 2.05f)
                    lineToRelative(1.23f, 1.23f)
                    curveToRelative(0.56f, -0.98f, 0.9f, -2.09f, 0.9f, -3.28f)
                    close()
                    moveTo(14.98f, 11.17f)
                    curveToRelative(0.0f, -0.06f, 0.02f, -0.11f, 0.02f, -0.17f)
                    lineTo(15.0f, 5.0f)
                    curveToRelative(0.0f, -1.66f, -1.34f, -3.0f, -3.0f, -3.0f)
                    reflectiveCurveTo(9.0f, 3.34f, 9.0f, 5.0f)
                    verticalLineToRelative(0.18f)
                    lineToRelative(5.98f, 5.99f)
                    close()
                    moveTo(4.27f, 3.0f)
                    lineTo(3.0f, 4.27f)
                    lineToRelative(6.01f, 6.01f)
                    lineTo(9.01f, 11.0f)
                    curveToRelative(0.0f, 1.66f, 1.33f, 3.0f, 2.99f, 3.0f)
                    curveToRelative(0.22f, 0.0f, 0.44f, -0.03f, 0.65f, -0.08f)
                    lineToRelative(1.66f, 1.66f)
                    curveToRelative(-0.71f, 0.33f, -1.5f, 0.52f, -2.31f, 0.52f)
                    curveToRelative(-2.76f, 0.0f, -5.3f, -2.1f, -5.3f, -5.1f)
                    lineTo(5.0f, 11.0f)
                    curveToRelative(0.0f, 3.41f, 2.72f, 6.23f, 6.0f, 6.72f)
                    lineTo(11.0f, 21.0f)
                    horizontalLineToRelative(2.0f)
                    verticalLineToRelative(-3.28f)
                    curveToRelative(0.91f, -0.13f, 1.77f, -0.45f, 2.54f, -0.9f)
                    lineTo(19.73f, 21.0f)
                    lineTo(21.0f, 19.73f)
                    lineTo(4.27f, 3.0f)
                    close()
                }
            }
            return _micOff!!
        }

    private var _micOff: ImageVector? = null

    val Warning: ImageVector
        get() {
            if (_warning != null) {
                return _warning!!
            }
            _warning = materialIcon(name = "Filled.Warning") {
                materialPath {
                    moveTo(1.0f, 21.0f)
                    horizontalLineToRelative(22.0f)
                    lineTo(12.0f, 2.0f)
                    lineTo(1.0f, 21.0f)
                    close()
                    moveTo(13.0f, 18.0f)
                    horizontalLineToRelative(-2.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineToRelative(2.0f)
                    verticalLineToRelative(2.0f)
                    close()
                    moveTo(13.0f, 14.0f)
                    horizontalLineToRelative(-2.0f)
                    verticalLineToRelative(-4.0f)
                    horizontalLineToRelative(2.0f)
                    verticalLineToRelative(4.0f)
                    close()
                }
            }
            return _warning!!
        }

    private var _warning: ImageVector? = null

    val Keyboard: ImageVector
        get() {
            if (_keyboard != null) {
                return _keyboard!!
            }
            _keyboard = materialIcon(name = "Filled.Keyboard") {
                materialPath {
                    moveTo(20.0f, 5.0f)
                    lineTo(4.0f, 5.0f)
                    curveToRelative(-1.1f, 0.0f, -1.99f, 0.9f, -1.99f, 2.0f)
                    lineTo(2.0f, 17.0f)
                    curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                    horizontalLineToRelative(16.0f)
                    curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                    lineTo(22.0f, 7.0f)
                    curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                    close()
                    moveTo(11.0f, 8.0f)
                    horizontalLineToRelative(2.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(-2.0f)
                    lineTo(11.0f, 8.0f)
                    close()
                    moveTo(11.0f, 11.0f)
                    horizontalLineToRelative(2.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(-2.0f)
                    verticalLineToRelative(-2.0f)
                    close()
                    moveTo(8.0f, 8.0f)
                    horizontalLineToRelative(2.0f)
                    verticalLineToRelative(2.0f)
                    lineTo(8.0f, 10.0f)
                    lineTo(8.0f, 8.0f)
                    close()
                    moveTo(8.0f, 11.0f)
                    horizontalLineToRelative(2.0f)
                    verticalLineToRelative(2.0f)
                    lineTo(8.0f, 13.0f)
                    verticalLineToRelative(-2.0f)
                    close()
                    moveTo(7.0f, 13.0f)
                    lineTo(5.0f, 13.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineToRelative(2.0f)
                    verticalLineToRelative(2.0f)
                    close()
                    moveTo(7.0f, 10.0f)
                    lineTo(5.0f, 10.0f)
                    lineTo(5.0f, 8.0f)
                    horizontalLineToRelative(2.0f)
                    verticalLineToRelative(2.0f)
                    close()
                    moveTo(16.0f, 17.0f)
                    lineTo(8.0f, 17.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineToRelative(8.0f)
                    verticalLineToRelative(2.0f)
                    close()
                    moveTo(16.0f, 13.0f)
                    horizontalLineToRelative(-2.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineToRelative(2.0f)
                    verticalLineToRelative(2.0f)
                    close()
                    moveTo(16.0f, 10.0f)
                    horizontalLineToRelative(-2.0f)
                    lineTo(14.0f, 8.0f)
                    horizontalLineToRelative(2.0f)
                    verticalLineToRelative(2.0f)
                    close()
                    moveTo(19.0f, 13.0f)
                    horizontalLineToRelative(-2.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineToRelative(2.0f)
                    verticalLineToRelative(2.0f)
                    close()
                    moveTo(19.0f, 10.0f)
                    horizontalLineToRelative(-2.0f)
                    lineTo(17.0f, 8.0f)
                    horizontalLineToRelative(2.0f)
                    verticalLineToRelative(2.0f)
                    close()
                }
            }
            return _keyboard!!
        }

    private var _keyboard: ImageVector? = null

    val Mic: ImageVector
        get() {
            if (_mic != null) {
                return _mic!!
            }
            _mic = materialIcon(name = "Filled.Mic") {
                materialPath {
                    moveTo(12.0f, 14.0f)
                    curveToRelative(1.66f, 0.0f, 2.99f, -1.34f, 2.99f, -3.0f)
                    lineTo(15.0f, 5.0f)
                    curveToRelative(0.0f, -1.66f, -1.34f, -3.0f, -3.0f, -3.0f)
                    reflectiveCurveTo(9.0f, 3.34f, 9.0f, 5.0f)
                    verticalLineToRelative(6.0f)
                    curveToRelative(0.0f, 1.66f, 1.34f, 3.0f, 3.0f, 3.0f)
                    close()
                    moveTo(17.3f, 11.0f)
                    curveToRelative(0.0f, 3.0f, -2.54f, 5.1f, -5.3f, 5.1f)
                    reflectiveCurveTo(6.7f, 14.0f, 6.7f, 11.0f)
                    lineTo(5.0f, 11.0f)
                    curveToRelative(0.0f, 3.41f, 2.72f, 6.23f, 6.0f, 6.72f)
                    lineTo(11.0f, 21.0f)
                    horizontalLineToRelative(2.0f)
                    verticalLineToRelative(-3.28f)
                    curveToRelative(3.28f, -0.48f, 6.0f, -3.3f, 6.0f, -6.72f)
                    horizontalLineToRelative(-1.7f)
                    close()
                }
            }
            return _mic!!
        }

    private var _mic: ImageVector? = null

    val CheckCircle: ImageVector
        get() {
            if (_checkCircle != null) {
                return _checkCircle!!
            }
            _checkCircle = materialIcon(name = "Filled.CheckCircle") {
                materialPath {
                    moveTo(12.0f, 2.0f)
                    curveTo(6.48f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
                    reflectiveCurveToRelative(4.48f, 10.0f, 10.0f, 10.0f)
                    reflectiveCurveToRelative(10.0f, -4.48f, 10.0f, -10.0f)
                    reflectiveCurveTo(17.52f, 2.0f, 12.0f, 2.0f)
                    close()
                    moveTo(10.0f, 17.0f)
                    lineToRelative(-5.0f, -5.0f)
                    lineToRelative(1.41f, -1.41f)
                    lineTo(10.0f, 14.17f)
                    lineToRelative(7.59f, -7.59f)
                    lineTo(19.0f, 8.0f)
                    lineToRelative(-9.0f, 9.0f)
                    close()
                }
            }
            return _checkCircle!!
        }

    private var _checkCircle: ImageVector? = null

    val CloudDownload: ImageVector
        get() {
            if (_cloudDownload != null) {
                return _cloudDownload!!
            }
            _cloudDownload = materialIcon(name = "Filled.CloudDownload") {
                materialPath {
                    moveTo(19.35f, 10.04f)
                    curveTo(18.67f, 6.59f, 15.64f, 4.0f, 12.0f, 4.0f)
                    curveTo(9.11f, 4.0f, 6.6f, 5.64f, 5.35f, 8.04f)
                    curveTo(2.34f, 8.36f, 0.0f, 10.91f, 0.0f, 14.0f)
                    curveToRelative(0.0f, 3.31f, 2.69f, 6.0f, 6.0f, 6.0f)
                    horizontalLineToRelative(13.0f)
                    curveToRelative(2.76f, 0.0f, 5.0f, -2.24f, 5.0f, -5.0f)
                    curveToRelative(0.0f, -2.64f, -2.05f, -4.78f, -4.65f, -4.96f)
                    close()
                    moveTo(17.0f, 13.0f)
                    lineToRelative(-5.0f, 5.0f)
                    lineToRelative(-5.0f, -5.0f)
                    horizontalLineToRelative(3.0f)
                    verticalLineTo(9.0f)
                    horizontalLineToRelative(4.0f)
                    verticalLineToRelative(4.0f)
                    horizontalLineToRelative(3.0f)
                    close()
                }
            }
            return _cloudDownload!!
        }

    private var _cloudDownload: ImageVector? = null

    val Settings: ImageVector
        get() {
            if (_settings != null) {
                return _settings!!
            }
            _settings = materialIcon(name = "Filled.Settings") {
                materialPath {
                    moveTo(19.14f, 12.94f)
                    curveToRelative(0.04f, -0.3f, 0.06f, -0.61f, 0.06f, -0.94f)
                    curveToRelative(0.0f, -0.32f, -0.02f, -0.64f, -0.07f, -0.94f)
                    lineToRelative(2.03f, -1.58f)
                    curveToRelative(0.18f, -0.14f, 0.23f, -0.41f, 0.12f, -0.61f)
                    lineToRelative(-1.92f, -3.32f)
                    curveToRelative(-0.12f, -0.22f, -0.37f, -0.29f, -0.59f, -0.22f)
                    lineToRelative(-2.39f, 0.96f)
                    curveToRelative(-0.5f, -0.38f, -1.03f, -0.7f, -1.62f, -0.94f)
                    lineTo(14.4f, 2.81f)
                    curveToRelative(-0.04f, -0.24f, -0.24f, -0.41f, -0.48f, -0.41f)
                    horizontalLineToRelative(-3.84f)
                    curveToRelative(-0.24f, 0.0f, -0.43f, 0.17f, -0.47f, 0.41f)
                    lineTo(9.25f, 5.35f)
                    curveTo(8.66f, 5.59f, 8.12f, 5.92f, 7.63f, 6.29f)
                    lineTo(5.24f, 5.33f)
                    curveToRelative(-0.22f, -0.08f, -0.47f, 0.0f, -0.59f, 0.22f)
                    lineTo(2.74f, 8.87f)
                    curveTo(2.62f, 9.08f, 2.66f, 9.34f, 2.86f, 9.48f)
                    lineToRelative(2.03f, 1.58f)
                    curveTo(4.84f, 11.36f, 4.8f, 11.69f, 4.8f, 12.0f)
                    reflectiveCurveToRelative(0.02f, 0.64f, 0.07f, 0.94f)
                    lineToRelative(-2.03f, 1.58f)
                    curveToRelative(-0.18f, 0.14f, -0.23f, 0.41f, -0.12f, 0.61f)
                    lineToRelative(1.92f, 3.32f)
                    curveToRelative(0.12f, 0.22f, 0.37f, 0.29f, 0.59f, 0.22f)
                    lineToRelative(2.39f, -0.96f)
                    curveToRelative(0.5f, 0.38f, 1.03f, 0.7f, 1.62f, 0.94f)
                    lineToRelative(0.36f, 2.54f)
                    curveToRelative(0.05f, 0.24f, 0.24f, 0.41f, 0.48f, 0.41f)
                    horizontalLineToRelative(3.84f)
                    curveToRelative(0.24f, 0.0f, 0.44f, -0.17f, 0.47f, -0.41f)
                    lineToRelative(0.36f, -2.54f)
                    curveToRelative(0.59f, -0.24f, 1.13f, -0.56f, 1.62f, -0.94f)
                    lineToRelative(2.39f, 0.96f)
                    curveToRelative(0.22f, 0.08f, 0.47f, 0.0f, 0.59f, -0.22f)
                    lineToRelative(1.92f, -3.32f)
                    curveToRelative(0.12f, -0.22f, 0.07f, -0.47f, -0.12f, -0.61f)
                    lineTo(19.14f, 12.94f)
                    close()
                    moveTo(12.0f, 15.6f)
                    curveToRelative(-1.98f, 0.0f, -3.6f, -1.62f, -3.6f, -3.6f)
                    reflectiveCurveToRelative(1.62f, -3.6f, 3.6f, -3.6f)
                    reflectiveCurveToRelative(3.6f, 1.62f, 3.6f, 3.6f)
                    reflectiveCurveTo(13.98f, 15.6f, 12.0f, 15.6f)
                    close()
                }
            }
            return _settings!!
        }

    private var _settings: ImageVector? = null

    val ArrowBack: ImageVector
        get() {
            if (_arrowBack != null) {
                return _arrowBack!!
            }
            _arrowBack = materialIcon(name = "AutoMirrored.Filled.ArrowBack", autoMirror = true) {
                materialPath {
                    moveTo(20.0f, 11.0f)
                    horizontalLineTo(7.83f)
                    lineToRelative(5.59f, -5.59f)
                    lineTo(12.0f, 4.0f)
                    lineToRelative(-8.0f, 8.0f)
                    lineToRelative(8.0f, 8.0f)
                    lineToRelative(1.41f, -1.41f)
                    lineTo(7.83f, 13.0f)
                    horizontalLineTo(20.0f)
                    verticalLineToRelative(-2.0f)
                    close()
                }
            }
            return _arrowBack!!
        }

    private var _arrowBack: ImageVector? = null


    val Sync: ImageVector
        get() {
            if (_sync != null) {
                return _sync!!
            }
            _sync = materialIcon(name = "Filled.Sync") {
                materialPath {
                    moveTo(12.0f, 4.0f)
                    lineTo(12.0f, 1.0f)
                    lineTo(8.0f, 5.0f)
                    lineToRelative(4.0f, 4.0f)
                    lineTo(12.0f, 6.0f)
                    curveToRelative(3.31f, 0.0f, 6.0f, 2.69f, 6.0f, 6.0f)
                    curveToRelative(0.0f, 1.01f, -0.25f, 1.97f, -0.7f, 2.8f)
                    lineToRelative(1.46f, 1.46f)
                    curveTo(19.54f, 15.03f, 20.0f, 13.57f, 20.0f, 12.0f)
                    curveToRelative(0.0f, -4.42f, -3.58f, -8.0f, -8.0f, -8.0f)
                    close()
                    moveTo(12.0f, 18.0f)
                    curveToRelative(-3.31f, 0.0f, -6.0f, -2.69f, -6.0f, -6.0f)
                    curveToRelative(0.0f, -1.01f, 0.25f, -1.97f, 0.7f, -2.8f)
                    lineTo(5.24f, 7.74f)
                    curveTo(4.46f, 8.97f, 4.0f, 10.43f, 4.0f, 12.0f)
                    curveToRelative(0.0f, 4.42f, 3.58f, 8.0f, 8.0f, 8.0f)
                    verticalLineToRelative(3.0f)
                    lineToRelative(4.0f, -4.0f)
                    lineToRelative(-4.0f, -4.0f)
                    verticalLineToRelative(3.0f)
                    close()
                }
            }
            return _sync!!
        }

    private var _sync: ImageVector? = null


    val Error: ImageVector
        get() {
            if (_error != null) {
                return _error!!
            }
            _error = materialIcon(name = "Filled.Error") {
                materialPath {
                    moveTo(12.0f, 2.0f)
                    curveTo(6.48f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
                    reflectiveCurveToRelative(4.48f, 10.0f, 10.0f, 10.0f)
                    reflectiveCurveToRelative(10.0f, -4.48f, 10.0f, -10.0f)
                    reflectiveCurveTo(17.52f, 2.0f, 12.0f, 2.0f)
                    close()
                    moveTo(13.0f, 17.0f)
                    horizontalLineToRelative(-2.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineToRelative(2.0f)
                    verticalLineToRelative(2.0f)
                    close()
                    moveTo(13.0f, 13.0f)
                    horizontalLineToRelative(-2.0f)
                    lineTo(11.0f, 7.0f)
                    horizontalLineToRelative(2.0f)
                    verticalLineToRelative(6.0f)
                    close()
                }
            }
            return _error!!
        }

    private var _error: ImageVector? = null


    val Refresh: ImageVector
        get() {
            if (_refresh != null) {
                return _refresh!!
            }
            _refresh = materialIcon(name = "Filled.Refresh") {
                materialPath {
                    moveTo(17.65f, 6.35f)
                    curveTo(16.2f, 4.9f, 14.21f, 4.0f, 12.0f, 4.0f)
                    curveToRelative(-4.42f, 0.0f, -7.99f, 3.58f, -7.99f, 8.0f)
                    reflectiveCurveToRelative(3.57f, 8.0f, 7.99f, 8.0f)
                    curveToRelative(3.73f, 0.0f, 6.84f, -2.55f, 7.73f, -6.0f)
                    horizontalLineToRelative(-2.08f)
                    curveToRelative(-0.82f, 2.33f, -3.04f, 4.0f, -5.65f, 4.0f)
                    curveToRelative(-3.31f, 0.0f, -6.0f, -2.69f, -6.0f, -6.0f)
                    reflectiveCurveToRelative(2.69f, -6.0f, 6.0f, -6.0f)
                    curveToRelative(1.66f, 0.0f, 3.14f, 0.69f, 4.22f, 1.78f)
                    lineTo(13.0f, 11.0f)
                    horizontalLineToRelative(7.0f)
                    verticalLineTo(4.0f)
                    lineToRelative(-2.35f, 2.35f)
                    close()
                }
            }
            return _refresh!!
        }

    private var _refresh: ImageVector? = null

    val Download: ImageVector
        get() {
            if (_download != null) {
                return _download!!
            }
            _download = materialIcon(name = "Filled.Download") {
                materialPath {
                    moveTo(5.0f, 20.0f)
                    horizontalLineToRelative(14.0f)
                    verticalLineToRelative(-2.0f)
                    horizontalLineTo(5.0f)
                    verticalLineTo(20.0f)
                    close()
                    moveTo(19.0f, 9.0f)
                    horizontalLineToRelative(-4.0f)
                    verticalLineTo(3.0f)
                    horizontalLineTo(9.0f)
                    verticalLineToRelative(6.0f)
                    horizontalLineTo(5.0f)
                    lineToRelative(7.0f, 7.0f)
                    lineTo(19.0f, 9.0f)
                    close()
                }
            }
            return _download!!
        }

    private var _download: ImageVector? = null

    val Delete: ImageVector
        get() {
            if (_delete != null) {
                return _delete!!
            }
            _delete = materialIcon(name = "Filled.Delete") {
                materialPath {
                    moveTo(6.0f, 19.0f)
                    curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                    horizontalLineToRelative(8.0f)
                    curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                    verticalLineTo(7.0f)
                    horizontalLineTo(6.0f)
                    verticalLineToRelative(12.0f)
                    close()
                    moveTo(19.0f, 4.0f)
                    horizontalLineToRelative(-3.5f)
                    lineToRelative(-1.0f, -1.0f)
                    horizontalLineToRelative(-5.0f)
                    lineToRelative(-1.0f, 1.0f)
                    horizontalLineTo(5.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(14.0f)
                    verticalLineTo(4.0f)
                    close()
                }
            }
            return _delete!!
        }

    private var _delete: ImageVector? = null

    val Backspace: ImageVector
        get() {
            if (_backspace != null) {
                return _backspace!!
            }
            _backspace = materialIcon(name = "AutoMirrored.Filled.Backspace", autoMirror = true) {
                materialPath {
                    moveTo(22.0f, 3.0f)
                    lineTo(7.0f, 3.0f)
                    curveToRelative(-0.69f, 0.0f, -1.23f, 0.35f, -1.59f, 0.88f)
                    lineTo(0.0f, 12.0f)
                    lineToRelative(5.41f, 8.11f)
                    curveToRelative(0.36f, 0.53f, 0.9f, 0.89f, 1.59f, 0.89f)
                    horizontalLineToRelative(15.0f)
                    curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                    lineTo(24.0f, 5.0f)
                    curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                    close()
                    moveTo(19.0f, 15.59f)
                    lineTo(17.59f, 17.0f)
                    lineTo(14.0f, 13.41f)
                    lineTo(10.41f, 17.0f)
                    lineTo(9.0f, 15.59f)
                    lineTo(12.59f, 12.0f)
                    lineTo(9.0f, 8.41f)
                    lineTo(10.41f, 7.0f)
                    lineTo(14.0f, 10.59f)
                    lineTo(17.59f, 7.0f)
                    lineTo(19.0f, 8.41f)
                    lineTo(15.41f, 12.0f)
                    lineTo(19.0f, 15.59f)
                    close()
                }
            }
            return _backspace!!
        }

    private var _backspace: ImageVector? = null

    val BackspaceOutlined: ImageVector
        get() {
            if (_backspaceOutlined != null) {
                return _backspaceOutlined!!
            }
            _backspaceOutlined = materialIcon(name = "AutoMirrored.Outlined.Backspace", autoMirror = true) {
                materialPath {
                    moveTo(22.0f, 3.0f)
                    lineTo(7.0f, 3.0f)
                    curveToRelative(-0.69f, 0.0f, -1.23f, 0.35f, -1.59f, 0.88f)
                    lineTo(0.0f, 12.0f)
                    lineToRelative(5.41f, 8.11f)
                    curveToRelative(0.36f, 0.53f, 0.9f, 0.89f, 1.59f, 0.89f)
                    horizontalLineToRelative(15.0f)
                    curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                    lineTo(24.0f, 5.0f)
                    curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                    close()
                    moveTo(22.0f, 19.0f)
                    lineTo(7.07f, 19.0f)
                    lineTo(2.4f, 12.0f)
                    lineToRelative(4.66f, -7.0f)
                    lineTo(22.0f, 5.0f)
                    verticalLineToRelative(14.0f)
                    close()
                    moveTo(10.41f, 17.0f)
                    lineTo(14.0f, 13.41f)
                    lineTo(17.59f, 17.0f)
                    lineTo(19.0f, 15.59f)
                    lineTo(15.41f, 12.0f)
                    lineTo(19.0f, 8.41f)
                    lineTo(17.59f, 7.0f)
                    lineTo(14.0f, 10.59f)
                    lineTo(10.41f, 7.0f)
                    lineTo(9.0f, 8.41f)
                    lineTo(12.59f, 12.0f)
                    lineTo(9.0f, 15.59f)
                    close()
                }
            }
            return _backspaceOutlined!!
        }

    private var _backspaceOutlined: ImageVector? = null


    val DeleteForever: ImageVector
        get() {
            if (_deleteForever != null) {
                return _deleteForever!!
            }
            _deleteForever = materialIcon(name = "Filled.DeleteForever") {
                materialPath {
                    moveTo(6.0f, 19.0f)
                    curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                    horizontalLineToRelative(8.0f)
                    curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                    lineTo(18.0f, 7.0f)
                    lineTo(6.0f, 7.0f)
                    verticalLineToRelative(12.0f)
                    close()
                    moveTo(8.46f, 11.88f)
                    lineToRelative(1.41f, -1.41f)
                    lineTo(12.0f, 12.59f)
                    lineToRelative(2.12f, -2.12f)
                    lineToRelative(1.41f, 1.41f)
                    lineTo(13.41f, 14.0f)
                    lineToRelative(2.12f, 2.12f)
                    lineToRelative(-1.41f, 1.41f)
                    lineTo(12.0f, 15.41f)
                    lineToRelative(-2.12f, 2.12f)
                    lineToRelative(-1.41f, -1.41f)
                    lineTo(10.59f, 14.0f)
                    lineToRelative(-2.13f, -2.12f)
                    close()
                    moveTo(15.5f, 4.0f)
                    lineToRelative(-1.0f, -1.0f)
                    horizontalLineToRelative(-5.0f)
                    lineToRelative(-1.0f, 1.0f)
                    lineTo(5.0f, 4.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(14.0f)
                    lineTo(19.0f, 4.0f)
                    close()
                }
            }
            return _deleteForever!!
        }

    private var _deleteForever: ImageVector? = null

    val SubdirectoryArrowLeft: ImageVector
        get() {
            if (_subdirectoryArrowLeft != null) {
                return _subdirectoryArrowLeft!!
            }
            _subdirectoryArrowLeft = materialIcon(name = "Filled.SubdirectoryArrowLeft") {
                materialPath {
                    moveTo(11.0f, 9.0f)
                    lineToRelative(1.42f, 1.42f)
                    lineTo(8.83f, 14.0f)
                    horizontalLineTo(18.0f)
                    verticalLineTo(4.0f)
                    horizontalLineToRelative(2.0f)
                    verticalLineToRelative(12.0f)
                    horizontalLineTo(8.83f)
                    lineToRelative(3.59f, 3.58f)
                    lineTo(11.0f, 21.0f)
                    lineToRelative(-6.0f, -6.0f)
                    lineToRelative(6.0f, -6.0f)
                    close()
                }
            }
            return _subdirectoryArrowLeft!!
        }

    private var _subdirectoryArrowLeft: ImageVector? = null

    val Lock: ImageVector
        get() {
            if (_lock != null) {
                return _lock!!
            }
            _lock = materialIcon(name = "Filled.Lock") {
                materialPath {
                    moveTo(18.0f, 8.0f)
                    horizontalLineToRelative(-1.0f)
                    lineTo(17.0f, 6.0f)
                    curveToRelative(0.0f, -2.76f, -2.24f, -5.0f, -5.0f, -5.0f)
                    reflectiveCurveTo(7.0f, 3.24f, 7.0f, 6.0f)
                    verticalLineToRelative(2.0f)
                    lineTo(6.0f, 8.0f)
                    curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                    verticalLineToRelative(10.0f)
                    curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                    horizontalLineToRelative(12.0f)
                    curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                    lineTo(20.0f, 10.0f)
                    curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                    close()
                    moveTo(12.0f, 17.0f)
                    curveToRelative(-1.1f, 0.0f, -2.0f, -0.9f, -2.0f, -2.0f)
                    reflectiveCurveToRelative(0.9f, -2.0f, 2.0f, -2.0f)
                    reflectiveCurveToRelative(2.0f, 0.9f, 2.0f, 2.0f)
                    reflectiveCurveToRelative(-0.9f, 2.0f, -2.0f, 2.0f)
                    close()
                    moveTo(15.1f, 8.0f)
                    lineTo(8.9f, 8.0f)
                    lineTo(8.9f, 6.0f)
                    curveToRelative(0.0f, -1.71f, 1.39f, -3.1f, 3.1f, -3.1f)
                    curveToRelative(1.71f, 0.0f, 3.1f, 1.39f, 3.1f, 3.1f)
                    verticalLineToRelative(2.0f)
                    close()
                }
            }
            return _lock!!
        }

    private var _lock: ImageVector? = null


    val KeyboardArrowUp: ImageVector
        get() {
            if (_keyboardArrowUp != null) {
                return _keyboardArrowUp!!
            }
            _keyboardArrowUp = materialIcon(name = "Filled.KeyboardArrowUp") {
                materialPath {
                    moveTo(7.41f, 15.41f)
                    lineTo(12.0f, 10.83f)
                    lineToRelative(4.59f, 4.58f)
                    lineTo(18.0f, 14.0f)
                    lineToRelative(-6.0f, -6.0f)
                    lineToRelative(-6.0f, 6.0f)
                    close()
                }
            }
            return _keyboardArrowUp!!
        }

    private var _keyboardArrowUp: ImageVector? = null

    val Stop: ImageVector
        get() {
            if (_stop != null) {
                return _stop!!
            }
            _stop = materialIcon(name = "Filled.Stop") {
                materialPath {
                    moveTo(6.0f, 6.0f)
                    horizontalLineToRelative(12.0f)
                    verticalLineToRelative(12.0f)
                    horizontalLineTo(6.0f)
                    close()
                }
            }
            return _stop!!
        }

    private var _stop: ImageVector? = null


    val Search: ImageVector
        get() {
            if (_search != null) {
                return _search!!
            }
            _search = materialIcon(name = "Filled.Search") {
                materialPath(pathFillType = PathFillType.EvenOdd) {
                    moveTo(15.5f, 14.0f)
                    horizontalLineToRelative(-0.79f)
                    lineToRelative(-0.28f, -0.27f)
                    curveTo(15.41f, 12.59f, 16.0f, 11.11f, 16.0f, 9.5f)
                    curveTo(16.0f, 5.91f, 13.09f, 3.0f, 9.5f, 3.0f)
                    reflectiveCurveTo(3.0f, 5.91f, 3.0f, 9.5f)
                    reflectiveCurveTo(5.91f, 16.0f, 9.5f, 16.0f)
                    curveToRelative(1.61f, 0.0f, 3.09f, -0.59f, 4.23f, -1.57f)
                    lineToRelative(0.27f, 0.28f)
                    verticalLineToRelative(0.79f)
                    lineToRelative(5.0f, 4.99f)
                    lineTo(20.49f, 19.0f)
                    lineTo(15.5f, 14.0f)
                    close()
                    moveToRelative(-6.0f, 0.0f)
                    curveTo(7.01f, 14.0f, 5.0f, 11.99f, 5.0f, 9.5f)
                    reflectiveCurveTo(7.01f, 5.0f, 9.5f, 5.0f)
                    reflectiveCurveTo(14.0f, 7.01f, 14.0f, 9.5f)
                    reflectiveCurveTo(11.99f, 14.0f, 9.5f, 14.0f)
                    close()
                }
            }
            return _search!!
        }

    private var _search: ImageVector? = null

    val Send: ImageVector
        get() {
            if (_send != null) {
                return _send!!
            }
            _send = materialIcon(name = "Filled.Send") {
                materialPath {
                    moveTo(2.01f, 21.0f)
                    lineTo(23.0f, 12.0f)
                    lineTo(2.01f, 3.0f)
                    lineTo(2.0f, 10.0f)
                    lineToRelative(15.0f, 2.0f)
                    lineToRelative(-15.0f, 2.0f)
                    close()
                }
            }
            return _send!!
        }

    private var _send: ImageVector? = null

    val ArrowForward: ImageVector
        get() {
            if (_arrowForward != null) {
                return _arrowForward!!
            }
            _arrowForward = materialIcon(name = "AutoMirrored.Filled.ArrowForward", autoMirror = true) {
                materialPath {
                    moveTo(12.0f, 4.0f)
                    lineToRelative(-1.41f, 1.41f)
                    lineTo(16.17f, 11.0f)
                    horizontalLineTo(4.0f)
                    verticalLineToRelative(2.0f)
                    horizontalLineToRelative(12.17f)
                    lineToRelative(-5.58f, 5.59f)
                    lineTo(12.0f, 20.0f)
                    lineToRelative(8.0f, -8.0f)
                    close()
                }
            }
            return _arrowForward!!
        }

    private var _arrowForward: ImageVector? = null

    val LockOutlined: ImageVector
        get() {
            if (_lockOutlined != null) {
                return _lockOutlined!!
            }
            _lockOutlined = materialIcon(name = "Outlined.Lock") {
                materialPath {
                    moveTo(18.0f, 8.0f)
                    horizontalLineToRelative(-1.0f)
                    lineTo(17.0f, 6.0f)
                    curveToRelative(0.0f, -2.76f, -2.24f, -5.0f, -5.0f, -5.0f)
                    reflectiveCurveTo(7.0f, 3.24f, 7.0f, 6.0f)
                    verticalLineToRelative(2.0f)
                    lineTo(6.0f, 8.0f)
                    curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                    verticalLineToRelative(10.0f)
                    curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                    horizontalLineToRelative(12.0f)
                    curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                    lineTo(20.0f, 10.0f)
                    curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                    close()
                    moveTo(9.0f, 6.0f)
                    curveToRelative(0.0f, -1.66f, 1.34f, -3.0f, 3.0f, -3.0f)
                    reflectiveCurveToRelative(3.0f, 1.34f, 3.0f, 3.0f)
                    verticalLineToRelative(2.0f)
                    lineTo(9.0f, 8.0f)
                    lineTo(9.0f, 6.0f)
                    close()
                    moveTo(18.0f, 20.0f)
                    lineTo(6.0f, 20.0f)
                    lineTo(6.0f, 10.0f)
                    horizontalLineToRelative(12.0f)
                    verticalLineToRelative(10.0f)
                    close()
                    moveTo(12.0f, 17.0f)
                    curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                    reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
                    reflectiveCurveToRelative(-2.0f, 0.9f, -2.0f, 2.0f)
                    reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
                    close()
                }
            }
            return _lockOutlined!!
        }

    private var _lockOutlined: ImageVector? = null


    inline fun materialIcon(
        name: String,
        autoMirror: Boolean = false,
        block: ImageVector.Builder.() -> ImageVector.Builder
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = MaterialIconDimension.dp,
        defaultHeight = MaterialIconDimension.dp,
        viewportWidth = MaterialIconDimension,
        viewportHeight = MaterialIconDimension,
        autoMirror = autoMirror
    ).block().build()

    const val MaterialIconDimension = 24f

    inline fun ImageVector.Builder.materialPath(
        fillAlpha: Float = 1f,
        strokeAlpha: Float = 1f,
        pathFillType: PathFillType = DefaultFillType,
        pathBuilder: PathBuilder.() -> Unit
    ) =
// TODO: b/146213225
// Some of these defaults are already set when parsing from XML, but do not currently exist
        // when added programmatically. We should unify these and simplify them where possible.
        path(
            fill = SolidColor(Color.Black),
            fillAlpha = fillAlpha,
            stroke = null,
            strokeAlpha = strokeAlpha,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Bevel,
            strokeLineMiter = 1f,
            pathFillType = pathFillType,
            pathBuilder = pathBuilder
        )

}
