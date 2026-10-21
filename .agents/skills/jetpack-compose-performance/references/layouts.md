## 1. Layout coordinates, rects, and visibility tracking

`Modifier.onGloballyPositioned` executes on every layout pass across the entire
Compose hierarchy whenever any node is placed or moved, making it a frequent
source of frame drops, unnecessary callbacks, and layout feedback loops. Use
targeted modern APIs instead:

### 1. Viewport and dwell tracking: use `Modifier.onVisibilityChanged`

Avoid calculating viewport intersections manually inside `onGloballyPositioned`.
Use **`Modifier.onVisibilityChanged`** (with `minFractionVisible` and optional
`minDurationMs` dwell thresholds) for:

- **Impression Logging**: Ensures analytics fire only when an item actually enters the viewport and stays visible for a minimum duration (preventing premature logging on pre-fetched lazy items).
- **Deferring Lookahead Shared Transitions** : Attaches `Modifier.sharedBounds` or `Modifier.sharedElement` only when an item enters the viewport, eliminating Lookahead passes on offscreen items.


```kotlin
// Optimized: Viewport entry with dwell threshold
Box(
    modifier = Modifier.onVisibilityChanged(
        minFractionVisible = 0.5f,
        minDurationMs = 500L,
    ) { isVisible ->
        if (isVisible) {
            analytics.logImpression(itemId)
        }
    }
)
```

<br />

### 2. Local bounds and placement: prefer `onPlaced` or `onLayoutRectChanged`

- **Dimensions Only (`IntSize`)** : If you only need width and height, use `Modifier.onSizeChanged`. It executes only when the node's dimensions change, ignoring position shifts.
- **Local Coordinates after Placement** : Use `Modifier.onPlaced` or `Modifier.onLayoutRectChanged` when you need local coordinates relative to the parent without subscribing to global window or screen coordinate updates across the entire tree.

### 3. On-demand coordinate queries: avoid `MutableState` position tracking

Avoid continuously listening to coordinate callbacks (`onGloballyPositioned` or
`onPlaced`) solely to cache layout coordinates into a `MutableState` for later
use when an event occurs (such as showing a tooltip, menu, or handling a
gesture). Storing coordinates in state causes redundant callback executions on
every layout or scroll pass and risks recomposition trashing or backwards
writes.

#### A. In Custom Modifiers (`Modifier.Node`)

`requireLayoutCoordinates()` is an API on `Modifier.Node` (specifically
`DelegatableNode`), designed to pull coordinates on demand:

- **Query inside Event Handlers** : In custom modifier nodes (such as `PointerInputModifierNode` or `DrawModifierNode`), call `requireLayoutCoordinates()` directly inside gesture, touch, or draw callbacks at the moment the event occurs.
- **Eliminates continuous listeners** : You do not need to implement `LayoutAwareModifierNode` or listen to `onGloballyPositioned` continuously just to have coordinates available when an interaction happens.


```kotlin
// In a custom PointerInputModifierNode:
class InteractiveTooltipNode : Modifier.Node(), PointerInputModifierNode {
    override fun onPointerEvent(
        pointerEvent: PointerEvent,
        pass: PointerEventPass,
        bounds: IntSize,
    ) {
        // Query coordinates on-demand at the exact moment of the interaction
        if (isAttached) {
            val coordinates = requireLayoutCoordinates()
            val bounds = coordinates.boundsInRoot()
            showTooltip(bounds)
        }
    }
}
```

<br />

#### B. In high-level composable code (versus `Modifier.clickable`)

`requireLayoutCoordinates()` is strictly a `Modifier.Node` API---it is **not**
available in high-level composables, and `Modifier.clickable` does not provide
coordinates to its `onClick: () -> Unit` lambda:

- **Standard clicks (`Modifier.clickable`)** : If your click handler does not require exact screen coordinates, use `Modifier.clickable` directly without attaching any coordinate listeners.
- **Position-aware gestures (`Modifier.pointerInput`)** : If you need the exact tap position in composable code (for example, to anchor a drop-down menu at the touch point), use `Modifier.pointerInput` with `detectTapGestures` to receive the tap `Offset` directly on the event, rather than storing `onGloballyPositioned` bounds in `MutableState`:


```kotlin
// Optimized: Captures touch offset on event without onGloballyPositioned
Box(
    modifier = Modifier.pointerInput(Unit) {
        detectTapGestures { offset ->
            showMenuAt(offset)
        }
    }
)
```

<br />

### 4. Prohibit layout feedback loops (back-writing during Layout)

Never write to `MutableState` inside `onPlaced`, `onSizeChanged`, or
`onGloballyPositioned` if reading that state invalidates the layout, size, or
placement of the node being measured.

Writing to state in the Layout phase that is read in Composition creates a
**backwards write** , which triggers the **first-frame correctness problem**: the
first frame is rendered with invalid, default, or unsettled dimensions (such as
zero size or an incorrect offset), followed by a second frame recomposition that
causes visible visual popping, layout flashing, and dropped frames. If the
layout sizing continuously writes back a new value to state, it results in an
infinite recomposition loop.

Similarly, within the Layout phase itself, state changes must flow forward:
updating state during measurement and reading it in placement is acceptable, but
writing to state in placement that is read during measurement causes a
continuous remeasure loop.

If sizes or coordinates are needed for custom drawing or placement, read them
directly in the Draw or Layout phase using `Modifier.drawWithCache` or
`Modifier.layout` instead of passing them back to Composition. For more
information on phase flow and eliminating recomposition loops, see the
[Backwards Write documentation](https://developer.android.com/develop/ui/compose/performance/backwards-write).

## 2. Phase deferral for layout offsets and constraints

Defer reading animated or scroll-driven state values to the **Layout** phase by
wrapping reads in lambda-based layout modifiers. This bypasses the Composition
phase entirely on state changes:

- **Bad (Recomposes on every offset change)**:


  ```kotlin
  val offset = scrollState.value
  Box(modifier = Modifier.offset(x = offset.dp, y = 0.dp))
  ```

  <br />

- **Optimized (Skips Composition, evaluates in Layout phase)**:


  ```kotlin
  Box(modifier = Modifier.offset { IntOffset(scrollState.value, 0) })
  ```

  <br />

## 3. Minimizing layout tree depth and wrapper nodes

Every layout node in Compose incurs measurement, placement, and snapshot
tracking costs. Minimize layout hierarchy depth:

- **Avoid Decorative Wrapper Layouts** : Do not wrap a composable in an extra `Box` solely to apply a background, border, padding, or clickable modifier. Attach modifiers directly to the child composable.
- **Avoid Canvas Nodes for Decorative Backgrounds** : Avoid using the `Canvas` composable solely to add decorative drawings behind content. Use `Modifier.drawBehind` or `Modifier.drawWithCache` on the existing layout node to avoid adding an extra node to the layout tree.

## 4. Subcomposition overhead (`SubcomposeLayout` and `BoxWithConstraints`)

`SubcomposeLayout` (and composables built on top of it, such as
`BoxWithConstraints`) defers composition until the measurement pass, breaking
Compose's standard single-pass layout pipeline into multiple subcomposition
passes:

- **Avoid `BoxWithConstraints` for Simple Responsiveness** : Do not use `BoxWithConstraints` when standard modifiers (`Modifier.fillMaxWidth()`, `Modifier.weight()`, or `Modifier.aspectRatio()`) or flow layouts can achieve the same visual layout.
- **Avoid `BoxWithConstraints` inside Lazy Items** : Using `BoxWithConstraints` inside `LazyColumn` or `LazyRow` items introduces subcomposition overhead for every recycled and pre-fetched item, increasing scroll jank.
- **Prefer `Modifier.layout` or `LayoutModifierNode`** : When custom measurement or placement rules depend on incoming constraints, prefer using `Modifier.layout { measurable, constraints -> ... }` or implementing `LayoutModifierNode` rather than spawning a `SubcomposeLayout` or `BoxWithConstraints`.

## 5. Intrinsic measurements

Intrinsic measurements (`IntrinsicSize.Min`, `IntrinsicSize.Max`) query children
for their preferred size before the final measurement pass, effectively running
a two-pass layout:

- Use intrinsic measurements judiciously and only when siblings must match dimensions (for example, a `Divider` matching the height of adjacent text).
- Avoid chaining nested intrinsic measurements across deep hierarchies.