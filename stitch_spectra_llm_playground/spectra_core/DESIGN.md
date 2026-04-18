# Design System Specification: Kinetic Precision

## 1. Overview & Creative North Star: "The Synthetic Intelligence Lab"

The Creative North Star for this design system is **"The Synthetic Intelligence Lab."** We are moving away from the "friendly SaaS" aesthetic toward a high-fidelity, mission-critical environment. This is an interface for architects of intelligence, not just casual users.

The system rejects the "card-on-grey-background" template. Instead, it utilizes **Organic Brutalism**: a high-contrast, sharp-edged aesthetic that feels engineered rather than "drawn." We break the grid through intentional asymmetry—using heavy left-aligned typography contrasted against expansive, breathing negative space and technical "glow" states that signify active AI processes.

---

## 2. Colors: Tonal Depth & The "No-Line" Mandate

This system achieves structure through light, not borders. 

### The "No-Line" Rule
**Explicit Instruction:** Designers are prohibited from using 1px solid borders for sectioning or layout containment. 
*   **How to define boundaries:** Use background color shifts. A section of `surface_container_low` (#091328) sitting on a `background` (#060e20) provides a sophisticated, seamless transition that 1px lines cannot replicate.
*   **The "Ghost Border" Fallback:** If accessibility requires a stroke (e.g., in high-density data tables), use `outline_variant` at **15% opacity**. Never use 100% opaque strokes.

### Surface Hierarchy & Nesting
Treat the UI as a series of monolithic plates.
*   **Base:** `surface` (#060e20)
*   **Secondary Sections:** `surface_container` (#0f1930)
*   **Active/Hero Containers:** `surface_container_highest` (#192540)
*   **Nesting:** Place `surface_container_lowest` (#000000) inside `surface_container_high` (#141f38) to create a "recessed" terminal or code editor look.

### The "Glass & Gradient" Rule
To signify the "liquidity" of AI data, use Glassmorphism for floating overlays (Modals, Tooltips).
*   **Formula:** `surface_variant` at 60% opacity + `backdrop-blur: 12px`.
*   **Signature Textures:** For primary CTAs, use a linear gradient: `primary` (#8ff5ff) to `primary_container` (#00eefc) at a 135-degree angle. This adds a "lithium-ion" energy to the action.

---

## 3. Typography: Technical Authority

We use **Space Grotesk** for high-level brand moments and **Inter** for functional data.

*   **Display (Space Grotesk):** Set with `letter-spacing: -0.04em`. These are your "billboard" moments. Use `display-lg` (3.5rem) for model training status or system headers.
*   **Headlines (Space Grotesk):** Use `headline-sm` (1.5rem) for section titles. It provides a sharp, technical "editorial" feel.
*   **Body (Inter):** The workhorse. `body-md` (0.875rem) is the default. Ensure `line-height` is set to 1.6 for readability in dense AI logs.
*   **Data/Labels (Inter):** Use `label-sm` (0.6875rem) in **All Caps** with `letter-spacing: 0.1em` for metadata and status badges. This mimics the "spec sheet" aesthetic.

---

## 4. Elevation & Depth: Tonal Layering

We do not use traditional drop shadows. We use **Ambient Luminance.**

*   **The Layering Principle:** Depth is achieved by "stacking" the surface tiers. A `surface_container_highest` element on a `surface` background is the primary way to show elevation.
*   **Ambient Shadows:** When an element must float (e.g., a context menu), use a shadow color of `primary` (#8ff5ff) at **4% opacity** with a `60px` blur. This creates a "glow" rather than a shadow, suggesting the component is light-emitting.
*   **Sharp Corners:** Every element—from buttons to large containers—uses a **0px radius**. This reinforces the "Technical/Professional" request. Roundness is for toys; sharp edges are for tools.

---

## 5. Components

### Terminal-Like Logs
*   **Background:** `surface_container_lowest` (#000000).
*   **Text:** `on_surface_variant` (#a3aac4) for timestamps; `primary` (#8ff5ff) for active processes.
*   **Styling:** No borders. Use a `3.5rem` (16) padding at the top for breadcrumbs.

### Data Tables (The "Spectra" Grid)
*   **Header:** `surface_container_high` (#141f38) with `label-sm` typography.
*   **Rows:** Alternating between `surface` and `surface_container_low`. 
*   **Interaction:** On hover, a row should transition to `surface_container_highest` with a `2px` left-accent of `secondary` (#d674ff).

### Progress Steppers
*   **Track:** `outline_variant` (#40485d).
*   **Active State:** A sharp, `0px` box using the `primary` gradient. 
*   **Glow:** Active steps should have a `box-shadow: 0 0 15px rgba(143, 245, 255, 0.4)`.

### Chat Interfaces (LLM Training)
*   **User Bubbles:** `surface_container_high` (#141f38).
*   **AI Bubbles:** `surface_container_lowest` (#000000) with a subtle `secondary_dim` (#bb00fc) inner-glow on the left edge.
*   **Input Field:** A simple `0.1rem` (0.5) line of `primary` at the bottom of the input area. No box.

### Buttons
*   **Primary:** Background `primary` (#8ff5ff), Text `on_primary_fixed` (#003f43). No border. Sharp 0px corners.
*   **Secondary:** Ghost style. No background. Border is `outline` (#6d758c) at 20% opacity. Text is `on_surface`.
*   **Tertiary:** Text only. `label-md` bold, color `secondary` (#d674ff).

---

## 6. Do's and Don'ts

### Do:
*   **Do** use extreme vertical whitespace (`20` or `24` scale) between major sections to allow the technical data to "breathe."
*   **Do** use `secondary` (#d674ff) sparingly for "Success" or "Complete" states to contrast against the `primary` cyan.
*   **Do** utilize typography size shifts (e.g., a very large `display-md` number next to a very small `label-sm` unit) to create an editorial look.

### Don't:
*   **Don't use Rounded Corners.** The radius scale is strictly `0px`.
*   **Don't use Dividers.** If you feel the need for a line, try a `0.4rem` (2) spacing increase or a subtle shift from `surface_container_low` to `surface_container_high` instead.
*   **Don't use Standard Greys.** Every neutral in this system is tinted with blue or slate (`#060e20`). Pure #222222 or #333333 will break the immersion.
*   **Don't use high-opacity shadows.** If a component looks like it's "floating" in a void, the shadow is too dark. It should look like it's resting on a dark glass table.