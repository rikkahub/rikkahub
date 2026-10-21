Compose executes a frame in three strictly ordered, forward-flowing phases:
![Three forward-flowing phases: 1. Composition, 2. Layout, 3. Drawing](https://developer.android.com/static/develop/ui/compose/performance/images/compose_phases.png) **Figure 1.** The three phases of a Compose frame

1. **Composition** : Runs `@Composable` functions to build and update the UI tree.
2. **Layout**: Measures children and then places them.
3. **Drawing**: Emits canvas draw commands to render pixels to the screen.

Whenever a Compose `State` is **read** during any phase, Compose automatically
**records a dependency** between that state and the corresponding phase.

*** ** * ** ***

## What makes a write "backwards"?

A **backwards write** occurs whenever **a state is modified in a later phase**
(or in a downstream scope---meaning a composable scope executed later in order
within the same composition pass) **than where it was read**, forcing Compose to
recompose by scheduling an earlier phase or composable to run again. A backwards
write is an unoptimized recomposition loop.
![Example of backwards write through the recomposition loop phases](https://developer.android.com/static/develop/ui/compose/performance/images/recomposition-loop.png) **Figure 2.** Example of backwards write through the recomposition loop phases

## Consequences of backwards writes

Backwards writes aren't necessarily a bad thing, and they don't always trigger
crashes, but they are inefficient and can harm app performance in several ways:

- **Extra frame rendering and dropped frames**: A backwards write forces Compose to execute redundant composition passes across consecutive frames, wasting CPU and GPU resources and potentially causing jank.
- **First-frame correctness issues**: If your component requires a backwards write to resolve its final dimensions or state, the first frame is rendered with invalid, default, or unsettled data (such as zero size or an incorrect offset). This causes visible visual popping or layout flashing when the second frame renders.
- **Infinite recomposition loops**: If a state change alters layout sizing, and layout sizing continuously writes back a new value to state, you can risk creating an infinite frame loop where the screen constantly recomposes every frame without ever stabilizing.

## Forward flow through phases

State changes should always flow **forward** through the phases:

| **Read Phase** | **Write Context** | **Acceptable?** | **Why** |
|---|---|---|---|
| **Layout** (`Modifier.offset { }`) | **Composition** | Yes | Composition updates the state → Layout reads it later in the same frame without recomposing. |
| **Draw** (`graphicsLayer { }`, `drawBehind { }`) | **Composition** | Yes | Composition updates the state → Draw reads it in the final phase. Composition and Layout are skipped entirely. |
| **Draw** | **Layout** | Yes | Layout updates the state, and draw reads it, valid flow. |
| **Composition** | Event Callback (`onClick`, `onValueChange`) driving state change. **Note**: layout callbacks do not count as events. | Yes | An event mutates state that is used to drive composition. If the event happens out-of-frame (not in Composition, Layout or Draw), this is valid. |
| **Composition** | Coroutine (`LaunchedEffect`) | Yes - with caution | Asynchronously updates state in response to lifecycle/events. Writes from effects can be valid, but can point to inefficient state layering. They should be avoided where possible. |
| **Placement (In Layout)** | **Measure (In Layout)** | Yes | In Layout, updating the state then reading that state in placement is acceptable. |
| **Measure (In Layout)** | **Placement (In Layout)** | **No - backwards write** | Writing to state in placement that's then further in the read state, causes remeasure loop. |
| **Composition** | **Layout** (`onSizeChanged`, `LayoutModifier`) | **No** - **Backwards write** | Layout invalidates Composition → Recomposition loop. |
| **Composition** | **Draw** (`drawWithContent`, `Canvas`) | **No - Backwards write** | Draw invalidates Composition → Recomposition loop. |

## Phase combinations: Backwards versus forward

The following are examples of backwards writes within Compose and how you can
resolve them.

### Backwards: Reading in Composition, writing in Layout

- **What happens** : Composition reads `componentHeight` to determine what UI to emit. Later in the frame, the Layout phase measures or places views and writes a new value to `componentHeight` (for example, using `onSizeChanged`, `onGloballyPositioned`, or custom `LayoutModifier`).
- **Result** : Modifying `componentHeight` in Layout invalidates the Composition phase that was just completed. Note that `onSizeChanged` reports size after the layout measurement pass completes. If the updated state value stabilizes on the next pass, the recomposition may halt after **one extra
  frame** ; however, if the new value continues to alter the size, it results in an infinite frame loop. Furthermore, `onGloballyPositioned` runs after both layout and placement, making state writes inside it even more susceptible to continuous recomposition and relayout loops across consecutive frames.


```kotlin
// ❌ BAD: Read in Composition, Written in Layout (onSizeChanged)
@Composable
fun BadAspectRatioImage(painter: Painter) {
    var calculatedHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    // State read during COMPOSITION:
    Image(
        painter = painter,
        contentDescription = "Dynamic Image",
        modifier = Modifier
            .fillMaxWidth()
            .height(calculatedHeight)
            .onSizeChanged { size ->
                // State write during LAYOUT phase!
                // Triggers backwards write and recomposition pass
                val aspectRatio = 16f / 9f
                val widthDp = with(density) { size.width.toDp() }
                calculatedHeight = widthDp / aspectRatio
            }
    )
}

// ✅ GOOD: Measure and calculate aspect ratio height in Phase 2 (Layout) without recomposition
@Composable
fun GoodAspectRatioImage(
    painter: Painter,
    aspectRatio: Float = 16f / 9f,
    modifier: Modifier = Modifier
) {
    Layout(
        content = {
            Image(
                painter = painter,
                contentDescription = "Dynamic Image"
            )
        },
        modifier = modifier
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = (width / aspectRatio).toInt() // Illustrative, you can use Modifier.aspectRatio()
        val imageConstraints = constraints.copy(
            minWidth = width,
            maxWidth = width,
            minHeight = height,
            maxHeight = height
        )
        val placeable = measurables.first().measure(imageConstraints)
        layout(width, height) {
            placeable.placeRelative(0, 0)
        }
    }
}
```

<br />

### Backwards: Reading in Composition, writing in Drawing

- **What happens** : State is read in the Composable body (Composition phase), but mutated inside `Modifier.drawWithContent`, `Modifier.drawBehind`, or `Canvas` (Draw phase).
- **Result**: Draw phase mutates state → Composition invalidated → endless loop.


```kotlin
// ❌ BAD: Read in Composition, Written in Draw ()
@Composable
fun BadBackwardsWriteDraw() {
    var componentHeight by remember { mutableStateOf(0.dp) }
    // State read during COMPOSITION:
    Text(
        text = "Height is: $componentHeight",
        modifier = Modifier.drawBehind {
            // State write during the DRAW phase!
            // Invalidates Composition -> triggers recomposition loop!
            componentHeight = size.height.dp
        }
    )
}
```

<br />

### Backwards: Reading in Composition, writing in Composition (same phase)

- **What happens** : Reading `count` in composable function, and modifying `count` directly in another composables content slot after reading it.
- **Result**: The snapshot system records the read and subsequent write within the same composition pass, immediately invalidating the current scope.


```kotlin
// ❌ BAD: Direct write in Composable body after read
@Composable
fun BadCounter() {
    var count by remember { mutableIntStateOf(0) }
    Text("Count: $count") // State read in Composition
    Button(onClick = {}) {
        count++ // State write in Composition (Backwards write!)
    }
}
// Acceptable - but error-prone as someone may add a read before the write : Direct write in Composable body before read
@Composable
fun OkCounter() {
    var count by remember { mutableIntStateOf(0) }
    Button(onClick = {}) {
        count++ // State  write in Composition
    }
    Text("Count: $count") // State read in Composition
}
```

<br />

*** ** * ** ***

## Key rules to prevent backwards writes

1. **Don't write to state in `onGloballyPositioned`, `onSizeChanged`, or
   `LayoutModifier` if that state is read in Composition** as this causes the first-frame-correctness problem.
   - If layout coordinates or sizes are only needed for custom drawing, read them directly in the Draw or Layout phase (for example, using `Modifier.drawWithCache` or `Modifier.layout`).
   - **For Window-Level Sizing (`WindowWidthSizeClass`):** Hoist size observation to the window level. Composition branches on window size classes before local measurement occurs.
   - **Keep Composition Uniform:** Use a single custom Layout or components like `FlowRow` or `LazyVerticalGrid` that adjust measurement and placement during Phase 2 without recomposing or altering state used in composition.
   - **Use Sub-composition:** Use `BoxWithConstraints` or `SubcomposeLayout` when child composables must branch based on local width or height. Use caution: Subcomposition carries a performance cost and can usually be avoided.
   - **As a last resort:** Allow the first frame to be wrong, storing size in `onSizeChanged` to trigger a second frame recomposition. This causes visible layout popping, jank, and risks infinite loops.
2. **Don't mutate state after its read the first time in composition** :
   - Although you may safely write to `MutableState` objects during composition outside of a`SideEffect`, take special care to ensure that you don't write to a state that you may have previously read in composition. It's recommended to use `rememberUpdatedState` when this need for a state write in composition arises. Writing to a state during composition in another way is usually a sign of a missing effect or an inappropriately designed state or composable. Remember that composition is optimistic and always executes with the latest value of a state, so you may not see all state changes in recompositions. UI updates shouldn't be used as a way of handling one-time events, which makes it uncommon for a state's value to require being updated as a result of recomposing.
   - Avoid mutating states that are observed outside of composition (for example, `ViewModel` fields or `isVisible` flags). State writes that affect composition belong in event lambdas (`onClick`), coroutines (`LaunchedEffect`), or side effects (`SideEffect`). `rememberUpdatedState` is an exception as it is designed to mutate state that is only used in `@Composable` body.
3. **Defer state reads to the latest possible phase** :
   - Reading states in Draw (`Modifier.graphicsLayer { alpha = ... }`) or Layout (`Modifier.offset { IntOffset(...) }`) ensures that changes only invalidate Phase 2 or 3, completely skipping Phase 1 (Composition).