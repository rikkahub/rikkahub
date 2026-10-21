---
name: jetpack-compose-performance
description: Diagnose and optimize Android Jetpack Compose UI code. Use this skill
  when analyzing performance bottlenecks of existing code to minimize recomposition
  overhead and prevent dropped frames and UI lag.
license: Complete terms in LICENSE.txt
metadata:
  author: Google LLC
  last-updated: '2026-10-14'
  keywords:
  - Jetpack Compose
  - performance
  - UI development
  - optimization
  - recomposition
  - lazy layouts
  - graphics
  - stability
  - memory
  - allocation
  - jank
  - rendering
  - state computation
  - state derivation
  - snapshotFlow
  - produceState
  - derivedStateOf
  - computedStateOf
  - sharedElement
  - sharedBounds
  - CompositionLocal
---

## Foundational prerequisites

Before micro-optimizing composables, ensure standard production flags are in
place:

1. **Release Build**: Never profile or benchmark debuggable builds; ART runtime verification severely degrades performance.
2. **R8 Minification**: Keep R8 enabled to optimize bytecode and coroutine dispatch.
3. **Baseline Profiles**: Generate baseline profiles for critical user journeys (cold start, key scrolling feeds) so the ART VM pre-compiles and preloads classes ahead of time.

## Optimization audit workflow

1. **Exhaustive Scan** : Scan the target code against all 7 core categories in the following sections. Evaluate every `@Composable` function, modifier chain, and lazy-layout data model against all checklist rules rather than stopping after the first issue found.
2. **Mandatory Reference Read Before Flagging or Editing** : Whenever a file matches a rule in Categories 1--7, you **MUST** open and read the corresponding `references/*.md` file for the approved fix pattern and full scope guards **before** reporting a finding or editing code. Never rely on the summary bullets alone to write fixes.

## 1. State, snapshots, and stability ([references/state-and-stability.md](references/state-and-stability.md))

**MANDATORY** : Before flagging or fixing any issue in this category, you
**MUST** read [references/state-and-stability.md](references/state-and-stability.md).

- **Phase Deferral (Lambdas)** : Wrap frequently changing state reads in lambdas (such as `Modifier.offset` or `Modifier.graphicsLayer`) so reads occur in Layout or Draw phase rather than Composition.
- **Prohibit Backwards Writes (Verify Read-Before-Write Order)** : Never modify state in a later phase or after it has already been read in the same composition pass (`Read -> Write`, or `Composition Read -> Layout or Draw
  Write`). Prevent backwards writes across phases to avoid first-frame popping, jank, and infinite recomposition loops ([Backwards Write](references/android/develop/ui/compose/performance/backwards-write.md)). Do **not** flag a state assignment at the start of a composable (`Write ->
  Read` or `Composition Write -> Draw Read`) as a backwards write---a backwards write strictly requires a read of that state to precede the write.
- **Avoid Redundant State Wrapping in Composition (`Write -> Read`)** : Do not wrap synchronous values in `remember { mutableStateOf(...) }` only to overwrite them immediately at the top of composition before reading them (for example, `var path by remember { mutableStateOf(Path()) }; path =
  viewModel.getPath()`). While not a backwards write, this adds unnecessary slot table and `MutableState` snapshot overhead (and risks becoming a backwards write if a read is later added preceding the assignment); use a plain local `val` or read the value directly inside the target phase (such as `Modifier.drawWithCache`).
- **Explicit `remember` Keys \& Slot Overhead** : Always pass all input variables and composable parameters referenced inside `remember` as calculation keys. Remember that `remember` stores `keys + 1` objects in the slot table and checks key equality on every recomposition; only `remember` when allocations are heavy or identity must be preserved. Do **not** pass snapshot state values/properties (e.g. `state.value`) as keys when wrapping `derivedStateOf` (passing the `State` instance itself is fine if the instance reference can change).
- **`computedStateOf` vs `derivedStateOf` (Compose 1.13+)** : On Compose 1.13+, use `computedStateOf` for simple condition checks and filtered state from fast-changing inputs (such as `scrollState.value > 0`), which provides significantly faster invalidation than `derivedStateOf` (on Compose `< 1.13`, use `remember { derivedStateOf { ... } }`). Reserve `derivedStateOf` for heavy calculations that benefit from multi-read caching, or trivial calculations read frequently enough per composition (roughly `10 / numberOfStatesReadPerCalculation` times without invalidating between reads) to make up for the invalidation cost.
- **Synchronous State Derivation** : Audit for asynchronous `produceState` + `snapshotFlow` blocks observing Compose states (such as scroll positions or collection sizes); replace them with synchronous `remember { derivedStateOf
  { ... } }` (or `computedStateOf` on Compose 1.13+).
- **Trivial Primitives** : Prefer plain custom Kotlin getters (`get()`) over `derivedStateOf` or `computedStateOf` for simple boolean expressions and property lookups.
- **Collection State Tracking** : Back whole-list updates with standard `mutableListOf()` and `mutableStateOf(list, neverEqualPolicy())` with load-bearing assignments instead of `mutableStateListOf()`.
- **Snapshot Collections** : Use `SnapshotStateList.toList()` for repeated queries or iterations to avoid per-element read observation overhead.
- **Repeated State Reads** : Extract repeated Compose `State` property reads into a local variable at the top of the block.
- **State in Setters** : Avoid reading Compose `State` inside custom property setters; use a raw backing field when triggering change side-effects and batch multi-property updates.
- **Stability \& Skipping Trade-Offs (Skipping Isn't Free)** : Do not fixate on stability or treat `@Immutable`, `@Stable`, or `ImmutableList` as default solutions. Skipping a composable is not free: each parameter is stored in the slot table as if remembered, and all inputs must be compared on recomposition. [Strong Skipping Mode](references/android/develop/ui/compose/performance/stability/strongskipping.md) already skips composables with unstable parameters (including standard `kotlin.collections.List`) when instance equality (`===`) matches. Annotating models or using `ImmutableList` forces structural equality (`.equals()`), which can be significantly more expensive than recomposition itself for large collections or complex classes.
- **Never Add `@Stable`, `@Immutable`, or `ImmutableList` to Collection
  Models** : When writing or optimizing code, never add `@Immutable` or `@Stable` to models containing collections (`List`, `Set`, `Map`), and **never** recommend replacing `kotlin.collections.List<T>` with `ImmutableList<T>` (or adding `ImmutableList` to `stability_config.txt`). Calling `O(N)` `.equals()` on collections on every recomposition pass is more expensive than `O(1)` instance equality (`===`) under Strong Skipping Mode.
- **Scope Guard for Removing Existing `@Stable` / `@Immutable` Annotations
  (Large Lazy Datasets Only)** :
  - **When to Remove** : **Only** recommend removing existing `@Stable` or `@Immutable` annotations from data models when **both** conditions are met: (1) the model is used within a **lazy layout** (`LazyColumn`, `LazyRow`, `LazyGrid`, `Pager`, `ScalingLazyColumn`) as the input feed or item model, **and** (2) it contains a **large dataset or deep nested
    collection hierarchy** (e.g., feed streams, large lists of tags/items, or multi-level nested lists like `Post` with `List<Paragraph>` and `List<Markup>`), where recursive `O(N)` `.equals()` checks degrade scrolling and recomposition performance.
  - **When to Leave Untouched** : For all other existing `@Stable`, `@Immutable`, or `ImmutableList` usages in a codebase---such as non-lazy UI models, standalone components, or models with small/bounded collections (e.g., a card with a few badges, thumbnails, or metadata rows) where dataset size is small or unknown---**leave the existing
    annotations untouched and do not flag them** . Never blindly grep and flag all `@Stable`/`@Immutable` classes across a codebase without verifying they feed a lazy layout with a large dataset.
- **When to Add `@Stable` / `@Immutable` Under Strong Skipping Mode** : Flat `data class`es with `val` primitive/`String` properties in Compose modules are **already inferred as stable** by the compiler and use `.equals()` automatically---do not add redundant `@Immutable` annotations to them. Only add `@Stable` or `@Immutable` when the compiler cannot infer stability (e.g. classes from non-Compose modules or interfaces) **and** a data source (such as Room or MVI reducers) allocates new object instances for unchanged items on every emission, **provided** the class is small, flat, has no collections (`List`, `Set`, `Map`), and its `.equals()` check is trivial `O(1)`.
- **Make Composables Cheaper Instead of Forcing Skipping**: Do not overindex on recomposition counts. Skipping one composable during an active recomposition pass saves little time once composition is scheduled (leaving the house is the bulk of the cost). Focus on making composables cheap to execute, deferring state reads to Layout/Draw, and verifying with benchmarks rather than assuming skipping improves performance.

## 2. Layouts and measurement optimization ([references/layouts.md](references/layouts.md))

**MANDATORY** : Before flagging or fixing any issue in this category, you
**MUST** read [references/layouts.md](references/layouts.md).

- **Coordinates \& Placement** : Avoid `onGloballyPositioned`; use `Modifier.onVisibilityChanged` for viewport tracking, `Modifier.onPlaced` or `Modifier.onLayoutRectChanged` for local bounds, and query `requireLayoutCoordinates()` on demand inside `Modifier.Node` event handlers.
- **Anchor Floating Content via Custom `Layout`** : When attaching floating labels or tooltips to a component, use a custom `Layout` measuring and placing both children relative to each other. Never track `onGloballyPositioned` coordinates in `MutableState` to position floating content with `offset`, which causes a 1-frame lag and schedules a second composition pass.
- **Phase Deferral for Offsets** : Wrap dynamic offsets in lambdas (`Modifier.offset { IntOffset(...) }`) to skip Composition straight to Layout.
- **Tree Depth \& Decorative Wrappers** : Avoid redundant wrapper `Box`es and decorative `Canvas` nodes; attach modifiers directly or use `Modifier.drawBehind`.
- **Subcomposition Overhead** : Avoid `BoxWithConstraints` / `SubcomposeLayout` inside hot paths and lazy items; prefer standard modifiers, `Modifier.layout`, or `LayoutModifierNode`.
- **Intrinsic Measurements**: Use intrinsic measurements judiciously and avoid deeply nested intrinsic chains.

## 3. Lazy layouts optimization ([references/lazy-layouts.md](references/lazy-layouts.md))

**MANDATORY** : Before flagging or fixing any issue in this category, you
**MUST** read [references/lazy-layouts.md](references/lazy-layouts.md).

- **Lazy Layout Scope Guard** : Directives for `key` and `contentType` apply **strictly** to Lazy containers (`LazyColumn`, `LazyRow`, `LazyGrid`). Never flag standard non-lazy containers (`Column`, `Row`, `Box`) iterating over collections.
- **Mandatory `contentType` and Primitive `key`** : Always specify both primitive `key`s (`Int`, `Long`, `String`) **and** `contentType`s for all items in lazy layouts (`LazyColumn`, `LazyRow`, `LazyGrid`). When using `items` or `itemsIndexed`, never omit `contentType`: `contentType = { _, item -> item.type }`. Omitting `contentType` prevents Compose from reusing composition slots during scrolling.
- **In-Item Index Lookups** : Replace `list.indexOf(item)` with `itemsIndexed` to eliminate `O(N²)` lookups during layout passes.
- **Observable Selection \& State Queries** : Convert selection tracking from `mutableStateListOf` to an immutable `Set` in `MutableState` (`mutableStateOf(emptySet<String>())`), `mutableStateSetOf()`, or Map for `O(1)` lookups. Pass lambda providers (`isSelected: () -> Boolean`) to leaf composables to isolate recomposition from parent `items` scopes.
- **Propagate CompositionLocals in Lazy Items** : Avoid querying `CompositionLocal`s (`LocalContext.current`, `LocalDensity.current`, `LocalSharedTransitionScope.current`, scopes, etc.) inside individual lazy item composables; resolve them once in the parent container or screen and propagate the resolved values down as parameters.
- **Defer Shared Transitions in Lazy Items** : Put `Modifier.sharedBounds` or `Modifier.sharedElement` on lazy items behind visibility indicators (such as `Modifier.onVisibilityChanged` or viewport visibility checks) so offscreen or pre-fetched items do not instantiate transition scopes.
- **Item Count Derivations** : Read collection `.size` directly instead of wrapping list counts in `derivedStateOf`.
- **Small \& Bounded Lists** : Use standard `Row` or `Column` with `for` or `forEach` loops for small or bounded collections (e.g. 1 to 10 items) to eliminate lazy virtualization, measuring, and pre-fetching overhead.
- **Hoist Shared Flows** : Collect shared `Flow`s once in the parent container preceding lazy layouts; never call `collectAsStateWithLifecycle()` per visible item.
- **Avoid Side Effects in Pre-Fetched Items** : Never trigger analytics in `LaunchedEffect(Unit)` inside lazy items; use `Modifier.onVisibilityChanged` with dwell thresholds instead.

## 4. Animation and shared transitions ([references/animation.md](references/animation.md))

**MANDATORY** : Before flagging or fixing any issue in this category, you
**MUST** read [references/animation.md](references/animation.md).

- **Phase Deferral for Animated Properties** : Wrap fast-changing animated state reads (alpha, scale, rotation, color) in `Modifier.graphicsLayer { ...
  }` to bypass Composition and evaluate directly in the Draw phase.
- **Phase Deferral for Offsets \& Size** : Wrap animated offsets in `Modifier.offset { IntOffset(...) }` and animate constraints using `Modifier.layout { ... }` or `Modifier.animateBounds` to bypass recomposition during size transitions.
- **Defer Shared Transitions in Fast-Scrolling Lists** : Attach `Modifier.sharedBounds` or `Modifier.sharedElement` only when visible using `Modifier.onVisibilityChanged` to avoid modifier and observation instantiation costs on discarded pre-fetched items.
- **Lambda Providers** : Pass lambda providers (`() -> Float`) instead of raw animated values to child composables to keep state reads deferred to Draw or Layout.
- **Persistent `Animatable` in Nodes** : Store `Animatable` as a persistent member property on `Modifier.Node` and launch in `onAttach()` using `coroutineScope.launch`.

## 5. Effects, lifecycle, and threading ([effects-and-threading.md](references/effects-and-threading.md))

**MANDATORY** : Before flagging or fixing any issue in this category, you
**MUST** read [references/effects-and-threading.md](references/effects-and-threading.md).

- **Prohibit Direct Side-Effects in Body** : Never perform side-effects directly in the Composable body during composition; wrap them in `SideEffect`, `LaunchedEffect`, or user event callbacks.
- **Prohibit Non-Suspending `LaunchedEffect` \& Empty `DisposableEffect`** : Never use `LaunchedEffect` for synchronous operations (e.g. logging views or updating objects). Never use `DisposableEffect` with an empty `onDispose {}` block as a workaround. Use `SideEffect` instead (in Compose 1.12+, `SideEffect(keys) { ... }` supports keys directly; on Compose `<
  1.12`, `SideEffect` takes no arguments, so check the project's Compose version and track key changes with a `remember`ed holder inside `SideEffect { ... }`).
- **Cache Allocations in Body** : Wrap heavy object allocations (such as date formatters or complex paths) and expensive computations in `remember`.
- **Combine Related `LaunchedEffect`s** : Combine multiple `LaunchedEffect(Unit)` calls into a single `LaunchedEffect` with nested `launch { ... }` blocks to reduce effect node allocations and coroutine launcher overhead.
- **Effect Ordering \& Teardown Costs** : Respect effect execution order (`DisposableEffect` \> `SideEffect` \> Layout and Draw \> `LaunchedEffect` after frame). Note that canceling a `LaunchedEffect` incurs coroutine cancellation and teardown overhead; avoid cycling `LaunchedEffect` keys frequently during animations or scrolling.
- **Main Thread Offloading** : Offload blocking I/O, heavy file parsing, and database operations to `withContext(Dispatchers.IO)` using `produceState`.
- **No Item-Level Impression Effects in Lazy Layouts** : Never use `LaunchedEffect` or `DisposableEffect` inside lazy items for impression logging. Lazy items are pre-composed before entering the viewport, causing premature or false impressions. Use `Modifier.onVisibilityChanged` with dwell thresholds instead.
- **Lifecycle-Aware Flow Collection** : Use `collectAsStateWithLifecycle()` for UI Flow collections that actively produce background work on Android, and `collectAsState()` for lightweight in-memory StateFlows or non-Android scopes.
- **Hoist Receivers \& Listeners** : Register system listeners and `BroadcastReceiver`s once at the screen level using `DisposableEffect`, not inside lazy list items.
- **Guard Optional Effects** : Guard effects with `if (callback != null)` before launching to prevent allocating empty coroutines and effect nodes.
- **Prevent Stale Captures** : Use `rememberUpdatedState` when long-running or looping coroutines in `LaunchedEffect` reference callbacks or parameters.
- **Replace Legacy Timers** : Replace `Handler.postDelayed` or `TimerTask` with `LaunchedEffect` and `delay()`.

## 6. Graphics and custom drawing ([references/graphics-performance.md](references/graphics-performance.md))

**MANDATORY** : Before flagging or fixing any issue in this category, you
**MUST** read [references/graphics-performance.md](references/graphics-performance.md).

- **Cache Drawing Allocations** : Replace decorative `Canvas` nodes with `Modifier.drawWithCache` to cache `Path`, `Paint`, and `Brush` allocations.
- **Prefer AsyncImage instead of Image** : Use Coil's `AsyncImage` for loading images from the internet and `painterResource` for drawables. `Image` is not optimized for caching and loading images and may cause performance issues.
- **Scope Fast-Changing Uniforms** : `remember` the `RuntimeShader` and `ShaderBrush` in composition, update size uniforms in `drawWithCache`, and per-frame uniforms in `onDrawBehind`.
- **Graphics Path Object Reuse** : Prefer `Path.rewind()` over `reset()` in hot draw loops to retain allocated buffers without reallocation.
- **Value Classes \& Primitive Collections** : Avoid generic tuples (`Pair`, `Triple`) and generic sets (`HashSet<Long>`); use `androidx.collection.MutableLongSet` and `androidx.compose.ui.util.packInts`.

## 7. Custom modifiers: `Modifier.Node` ([Custom modifiers guide](references/android/develop/ui/compose/custom-modifiers.md))

- **Modifier.Node Migration** : Migrate legacy `Modifier.composed` to
  `ModifierNodeElement` + `Modifier.Node` (or `DelegatingNode` when delegating
  to sub-nodes such as `SuspendingPointerInputModifierNode`) with capability
  interfaces (`DrawModifierNode`, `LayoutModifierNode`,
  `SemanticsModifierNode`, `CompositionLocalConsumerModifierNode` using
  `currentValueOf(...)`) to eliminate per-node subcomposition overhead and
  enable node reuse (see [Create custom modifiers with Modifier.Node](references/android/develop/ui/compose/custom-modifiers.md)):


  ```kotlin
  // Modifier factory
  fun Modifier.circle(color: Color) = this then CircleElement(color)

  // ModifierNodeElement
  private data class CircleElement(
      val color: Color
  ) : ModifierNodeElement<CircleNode>() {
      override fun create() = CircleNode(color)

      override fun update(node: CircleNode) {
          node.color = color
      }
  }

  // Modifier.Node
  private class CircleNode(
      var color: Color
  ) : DrawModifierNode, Modifier.Node() {
      override fun ContentDrawScope.draw() {
          drawCircle(color)
      }
  }
  ```

  <br />

- **On-Demand Coordinates** : Query `requireLayoutCoordinates()` on demand
  inside pointer or draw handlers in `Modifier.Node` rather than implementing
  `GlobalPositionAwareModifierNode.onGloballyPositioned`.

**IMPORTANT** : For more examples on how to migrate common modifiers to
Modifier.Node, you must read [Create custom modifiers with Modifier.Node](references/android/develop/ui/compose/custom-modifiers.md).
