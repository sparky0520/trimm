# Trimm Brand Design Palette

This document outlines the official brand design palette for Trimm, specifically focusing on the dark theme inspired by shadcn-ui.

## 🎨 Color Palette

The Trimm application uses a deliberately restrained, high-contrast dark palette to ensure content stands out while maintaining a sleek, modern, and professional aesthetic.

| Color Name    | Hex Code  | Usage / Description |
| :---          | :---      | :--- |
| **Ink**       | `#09090B` | Deep black background used for the main surface and structural containers. |
| **Workspace** | `#18181B` | Slightly lighter dark background, often used for differentiated work areas (like the BrandBar or panels). |
| **Raised**    | `#27272A` | Elevated surfaces such as cards, modals, or floating UI elements. |
| **Border**    | `#3F3F46` | Subtle gray used for dividers, strokes, and boundaries. |
| **Cream**     | `#FAFAFA` | Primary high-contrast text and icon color. Stands out sharply against Ink. |
| **CreamRaised**|`#F4F4F5` | Secondary highlighted elements or slightly softer white. |
| **Muted**     | `#A1A1AA` | Secondary or placeholder text. Used for descriptions or non-interactive hints. |
| **Lavender**  | `#D4D4D8` | Used for subtle highlights or alternative text colors. |
| **Green**     | `#E4E4E7` | Despite the name, this acts as a softer off-white for intermediate emphasis. |
| **Coral**     | `#FAFAFA` | Same hex as Cream; contextually used for error backgrounds or alert text in specific areas. |
| **UserBlue**  | `#1B365D` | Deep blue used to differentiate user-specific elements (e.g., chat bubbles). |
| **DarkText**  | `#09090B` | Ink equivalent, typically used over light backgrounds (e.g., over a green success banner). |

## 🔤 Typography

Trimm uses system sans-serif fonts to ensure maximum readability and a clean native feel. 

### Brand Logo Text (Trimm)
When displaying the "Trimm" brand name in the UI (such as in the AppDrawer header or BrandBar), use the following typography specifications:
- **Font Family**: `FontFamily.SansSerif`
- **Font Weight**: `SemiBold`
- **Font Size**: `24sp`
- **Letter Spacing**: `-1sp` (tight tracking for a modern wordmark feel)
- **Color**: `Cream` (`#FAFAFA`)

### UI Text Hierarchies
- **Headers / Prominent UI Text**: `20sp`, `Bold`
- **Body / Primary Actions**: `14sp` to `16sp`, `Normal`
- **Muted / Hints (e.g. "START A NEW EDIT")**: `10sp` to `12sp`, `Bold`, with wider letter spacing (`1.4sp`) for emphasis.

> [!NOTE]
> The primary color theme is strictly a **dark mode** design. Any UI extensions should default to `Ink` for background and `Cream` for primary foreground elements.
