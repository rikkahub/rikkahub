package com.hefengfan.hffchat.ui.components.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

public val HeartIcon: ImageVector
    get() {
        if (_heartIcon != null) {
            return _heartIcon!!
        }
        _heartIcon = ImageVector.Builder(
            name = "HeartFilled",
            defaultWidth = 16.dp,
            defaultHeight = 16.dp,
            viewportWidth = 16f,
            viewportHeight = 16f
        ).apply {
            path(
                fill = SolidColor(Color(0xFF000000)),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 1.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter,
                strokeLineMiter = 1.0f,
                pathFillType = PathFillType.NonZero
            ) {
                moveTo(14.88f, 4.78079f)
                curveTo(14.7993f, 4.465f, 14.6748f, 4.162f, 14.51f, 3.8808f)
                curveTo(14.3518f, 3.5882f, 14.1493f, 3.3217f, 13.91f, 3.0907f)
                curveTo(13.563f, 2.7449f, 13.152f, 2.4698f, 12.7f, 2.2808f)
                curveTo(11.7902f, 1.9074f, 10.7698f, 1.9074f, 9.86f, 2.2808f)
                curveTo(9.4328f, 2.4616f, 9.0403f, 2.7154f, 8.7f, 3.0308f)
                lineTo(8.65003f, 3.09073f)
                lineTo(8.00001f, 3.74075f)
                lineTo(7.34999f, 3.09073f)
                lineTo(7.3f, 3.03079f)
                curveTo(6.9597f, 2.7154f, 6.5673f, 2.4616f, 6.14f, 2.2808f)
                curveTo(5.2302f, 1.9074f, 4.2098f, 1.9074f, 3.3f, 2.2808f)
                curveTo(2.848f, 2.4698f, 2.4371f, 2.7449f, 2.09f, 3.0907f)
                curveTo(1.8505f, 3.324f, 1.6451f, 3.59f, 1.48f, 3.8808f)
                curveTo(1.3226f, 4.1644f, 1.2016f, 4.4668f, 1.12f, 4.7808f)
                curveTo(1.0352f, 5.1072f, 0.9949f, 5.4436f, 1f, 5.7808f)
                curveTo(1.0005f, 6.0979f, 1.0408f, 6.4136f, 1.12f, 6.7207f)
                curveTo(1.2038f, 7.0308f, 1.3247f, 7.3296f, 1.48f, 7.6108f)
                curveTo(1.6477f, 7.8998f, 1.8529f, 8.1654f, 2.09f, 8.4008f)
                lineTo(8.00001f, 14.3108f)
                lineTo(13.91f, 8.40079f)
                curveTo(14.1471f, 8.1678f, 14.3492f, 7.9017f, 14.51f, 7.6108f)
                curveTo(14.6729f, 7.3321f, 14.7974f, 7.0327f, 14.88f, 6.7207f)
                curveTo(14.9592f, 6.4136f, 14.9995f, 6.0979f, 15f, 5.7808f)
                curveTo(15.0052f, 5.4436f, 14.9648f, 5.1072f, 14.88f, 4.7808f)
                close()
            }
        }.build()
        return _heartIcon!!
    }

private var _heartIcon: ImageVector? = null
