# Launcher artwork

`source.svg` is the supplied project artwork, preserved unchanged.

The Android vector foreground preserves the white wordmark and gray shadow. The solid black background is a separate adaptive layer. The mark is optically centered on a 108 dp canvas and fits within the central 66 dp safe region, so circle, squircle, and rounded-square masks keep the lettering intact.

Android 13 and newer receive a separate transparent monochrome layer containing only the wordmark, with no shadow or black square. The launcher supplies its themed colors. Android 8 and newer use adaptive icons; Android 6/7 use the density-independent legacy vector. App light/dark appearance remains strictly grayscale and is independent of launcher theming.

Guidance: https://developer.android.com/develop/ui/compose/system/icon_design_adaptive

Action icons are adapted from Google Material Design Icons (Apache 2.0): https://github.com/google/material-design-icons . The header wordmark uses the supplied SVG paths, tinted for light/dark contrast.
