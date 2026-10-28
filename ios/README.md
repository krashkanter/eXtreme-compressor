# Experimental Compressor for iOS

Initialized only. No Swift UI, wrapper implementation, Xcode project, or iOS build is included yet.

The future app will call the shared C codec through a public C header and a Swift module or bridging header. It must support EXTC v1 images and v2 image containers, rejecting unknown versions and media types before allocating image buffers.

Design direction: native SwiftUI, system light and dark appearances only, monochrome controls, and native Liquid Glass for navigation and floating controls. Image content stays unobscured. Respect Reduce Motion, Reduce Transparency, Dynamic Type, and VoiceOver. The supplied SVG is preserved in `artwork/source.svg` for future icon exports. No banner is included.

Planned flows: share extension for a single image, image import, folder conversion, and an EXTC document viewer. Mobile memory limits and cancellable processing must be shared with the Android implementation.
