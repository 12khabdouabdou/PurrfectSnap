package me.eternal.purrfectsnap.core.features.impl.experiments.router.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.RelativeLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import me.eternal.purrfectsnap.core.R
import me.eternal.purrfectsnap.core.features.impl.experiments.router.Route
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

@Composable
fun RoutePickerMap(
    startGeopoint: GeoPoint?,
    endGeopoint: GeoPoint?,
    route: Route?,
    onMapTap: (GeoPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    
    val mapView = remember {
        Configuration.getInstance().load(
            context,
            context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        )
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(15.0)
            controller.setCenter(GeoPoint(37.7749, -122.4194))
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            )
        }
    }

    DisposableEffect(mapView) {
        onDispose {
            mapView.onDetach()
        }
    }

    DisposableEffect(startGeopoint, endGeopoint, route) {
        mapView.overlays.clear()

        startGeopoint?.let { point ->
            val startMarker = createMarker(
                context = context,
                mapView = mapView,
                point = point,
                color = android.graphics.Color.parseColor("#4CAF50"),
                title = "Start"
            )
            mapView.overlays.add(startMarker)
            mapView.controller.animateTo(point)
        }

        endGeopoint?.let { point ->
            val endMarker = createMarker(
                context = context,
                mapView = mapView,
                point = point,
                color = android.graphics.Color.parseColor("#F44336"),
                title = "End"
            )
            mapView.overlays.add(endMarker)
        }

        route?.let { r ->
            val polyline = Polyline(mapView).apply {
                setPoints(r.coordinates)
                outlinePaint.color = android.graphics.Color.parseColor("#2196F3")
                outlinePaint.strokeWidth = 8f
            }
            mapView.overlays.add(polyline)

            val bounds = org.osmdroid.util.BoundingBox.fromGeoPoints(
                r.coordinates.map { it }
            )
            mapView.zoomToBoundingBox(bounds, true)
        }

        if (startGeopoint == null && route == null) {
            mapView.setOnTouchListener { _, event ->
                val geoPoint = mapView.getProjection().fromPixels(
                    event.x.toInt(),
                    event.y.toInt()
                ) as GeoPoint
                onMapTap(geoPoint)
                true
            }
        } else {
            mapView.setOnTouchListener(null)
        }

        mapView.invalidate()
        onDispose { }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier.fillMaxSize()
    )
}

private fun createMarker(
    context: Context,
    mapView: MapView,
    point: GeoPoint,
    color: Int,
    title: String,
): Marker {
    return Marker(mapView).apply {
        position = point
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        this.title = title
        icon = createColoredPin(context, color)
    }
}

private fun createColoredPin(context: Context, color: Int): android.graphics.drawable.Drawable {
    val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    
    val paint = Paint().apply {
        this.color = color
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    
    canvas.drawCircle(32f, 24f, 20f, paint)
    
    val path = android.graphics.Path().apply {
        moveTo(32f, 64f)
        lineTo(16f, 28f)
        lineTo(48f, 28f)
        close()
    }
    canvas.drawPath(path, paint)
    
    val whitePaint = Paint().apply {
        color = android.graphics.Color.WHITE
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    canvas.drawCircle(32f, 24f, 8f, whitePaint)
    
    return android.graphics.drawable.BitmapDrawable(
        context.resources,
        bitmap
    )
}
