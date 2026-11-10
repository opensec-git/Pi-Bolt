package opensec.pibolt.app.ui.session.views

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Space
import opensec.pibolt.protocol.session.MobileViews
import opensec.pibolt.protocol.session.VLocation
import java.util.Locale

/**
 * The kit's `location` (docs/mobile-ui.md#charts-tables-and-places): a card with the place's name, address and
 * coordinates. A tap hands a `geo:` link to the maps app the user chose; no map is drawn, so no map tiles are loaded.
 */
@Composable
fun ViewLocation(place: VLocation, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val type = Pi.type
    val context = LocalContext.current
    val shape = RoundedCornerShape(Radius.tile)
    val uri = remember(place) { MobileViews.geoUri(place) }
    var failed by remember(place) { mutableStateOf(false) }
    val coords = if (place.lat != null && place.lon != null) String.format(Locale.US, "%.5f, %.5f", place.lat, place.lon) else null
    val open: (() -> Unit)? = uri?.let { link ->
        {
            failed = try {
                context.startActivity(Intent(Intent.ACTION_VIEW, link.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                false
            } catch (_: ActivityNotFoundException) {
                true
            } catch (_: SecurityException) {
                true
            }
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(if (open != null) Modifier.pressable(open, shape = shape, pressedScale = 0.98f, label = "Open ${place.name} in maps") else Modifier)
                .semantics(mergeDescendants = true) {}
                .background(colors.surface1, shape)
                .border(1.dp, colors.line, shape)
                .padding(horizontal = Space.lg, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Place, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(IconSize.md))
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(place.name, style = type.rowTitle, color = colors.ink1, maxLines = 2, overflow = TextOverflow.Ellipsis)
                place.address?.let { Text(it, style = type.rowMeta, color = colors.ink3, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                coords?.let { Text(it, style = type.monoSmall, color = colors.ink3, maxLines = 1) }
            }
            if (open != null) {
                Spacer(Modifier.width(Space.sm))
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(IconSize.sm))
            }
        }
        if (failed) {
            Text(
                "No maps app on this phone can open it",
                style = type.meta,
                color = colors.ink3,
                modifier = Modifier.padding(horizontal = Space.sm).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
