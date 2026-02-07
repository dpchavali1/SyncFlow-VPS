# App Icons for PWA

The SyncFlow web app requires icon files for Progressive Web App (PWA) functionality.

## Required Icons

- `icon-192.png` - 192x192 pixels
- `icon-512.png` - 512x512 pixels
- `favicon.ico` - 32x32 pixels (or multi-size)

## Creating Icons

### Option 1: Use Online Tool

1. Create your logo/icon design
2. Go to [https://realfavicongenerator.net/](https://realfavicongenerator.net/)
3. Upload your logo
4. Download the generated icons
5. Copy `icon-192.png`, `icon-512.png`, and `favicon.ico` to this directory

### Option 2: Use Image Editor

**Design Requirements:**
- **Style**: Modern, clean, Material Design 3
- **Colors**:
  - Primary: #0ea5e9 (blue)
  - Accent: White or gradient
- **Icon**: Smartphone with message bubble or similar
- **Background**: Solid color or gradient
- **Safe area**: Keep important elements within center 80%

**Tools:**
- Figma (free, web-based)
- Adobe Illustrator
- Sketch (macOS)
- Inkscape (free, cross-platform)

### Option 3: Use AI Generation

1. Use DALL-E, Midjourney, or similar:
   ```
   Prompt: "Modern mobile app icon, smartphone with message bubble,
   blue gradient background, Material Design 3 style, clean and minimal"
   ```
2. Resize to required dimensions
3. Export as PNG

## Temporary Placeholder

Until you create custom icons, you can use the app without icons, but some PWA features may not work perfectly.

## Icon Specifications

### icon-192.png
- **Size**: 192x192 pixels
- **Format**: PNG
- **Purpose**: Android homescreen, PWA install
- **Safe area**: 144x144 (center)

### icon-512.png
- **Size**: 512x512 pixels
- **Format**: PNG
- **Purpose**: Splash screen, app stores
- **Safe area**: 384x384 (center)

### favicon.ico
- **Size**: 32x32 pixels (or multi-size)
- **Format**: ICO
- **Purpose**: Browser tab icon

## Testing Icons

After adding icons:

1. Rebuild the app: `npm run build`
2. Test PWA install on Chrome:
   - Open app in Chrome
   - Click install icon in address bar
   - Check if icon appears correctly
3. Test on mobile:
   - Open in mobile browser
   - Add to homescreen
   - Verify icon on homescreen

## Design Tips

1. **Keep it simple**: Icon should be recognizable at small sizes
2. **Use consistent colors**: Match app theme
3. **High contrast**: Ensure visibility on various backgrounds
4. **Test on both light and dark backgrounds**
5. **No text**: Icons should be symbolic, not text-based
6. **Export at 2x resolution** for sharpness

## Current Status

⚠️ **Icons needed**: Please create and add the icon files to this directory.

Once added, the PWA will have full install functionality with proper branding.
